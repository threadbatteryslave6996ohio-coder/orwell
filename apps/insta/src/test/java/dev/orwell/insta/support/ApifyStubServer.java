package dev.orwell.insta.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for {@code api.apify.com} that records what was asked of it and replies with canned
 * responses. Every test here is about the request we send or the response we make of it, so a real
 * socket is worth more than a mocked HTTP client: it exercises the URLs we build, the headers we
 * set, the bodies we serialize, and — for the asynchronous path — the whole start / poll / read
 * dataset / read OUTPUT sequence.
 *
 * <p>{@link #responds} drives both run paths: a non-2xx status fails the run at its first call,
 * whichever endpoint that is, so a failure test does not need to know which path it is exercising.
 */
public final class ApifyStubServer implements AutoCloseable {
    public static final String RUN_ID = "run-1";
    public static final String DATASET_ID = "dataset-1";
    public static final String KEY_VALUE_STORE_ID = "kvs-1";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int datasetStatus = 200;
    private volatile String datasetBody = "[]";
    private volatile String outputBody;
    private volatile String runStatus = "SUCCEEDED";

    private ApifyStubServer(HttpServer server) {
        this.server = server;
    }

    public static ApifyStubServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ApifyStubServer stub = new ApifyStubServer(server);
        server.createContext("/", stub::handle);
        server.start();
        return stub;
    }

    /** The dataset the run produces, or — for a non-2xx status — the failure it produces instead. */
    public void responds(int status, String body) {
        this.datasetStatus = status;
        this.datasetBody = body;
    }

    /** The run's {@code OUTPUT} record. Null (the default) makes the record a 404, as Apify does. */
    public void outputs(String json) {
        this.outputBody = json;
    }

    /** The status the run settles in. Anything but {@code SUCCEEDED} is a failed lookup. */
    public void runSettlesAs(String status) {
        this.runStatus = status;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return requests;
    }

    public Request lastRequest() {
        return requests.get(requests.size() - 1);
    }

    /** The call that started the run — where the actor input and {@code maxItems} live. */
    public Request runStart() {
        return requests.stream()
                .filter(request -> request.path().endsWith("/runs")
                        || request.path().endsWith("/run-sync-get-dataset-items"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No actor run was started."));
    }

    public boolean sawRequestTo(String pathFragment) {
        return requests.stream().anyMatch(request -> request.path().contains(pathFragment));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"), body));

        if (path.endsWith("/run-sync-get-dataset-items")) {
            respond(exchange, datasetStatus, datasetBody);
        } else if (path.endsWith("/runs")) {
            // A run that cannot even start reports the configured failure here.
            if (datasetStatus < 200 || datasetStatus >= 300) {
                respond(exchange, datasetStatus, datasetBody);
            } else {
                respond(exchange, 201, runObject());
            }
        } else if (path.endsWith("/abort")) {
            respond(exchange, 200, runObject());
        } else if (path.startsWith("/v2/actor-runs/")) {
            respond(exchange, 200, runObject());
        } else if (path.endsWith("/items")) {
            respond(exchange, 200, datasetBody);
        } else if (path.endsWith("/records/OUTPUT")) {
            if (outputBody == null) {
                respond(exchange, 404, "{\"error\":{\"type\":\"record-not-found\"}}");
            } else {
                respond(exchange, 200, outputBody);
            }
        } else {
            respond(exchange, 404, "{\"error\":{\"type\":\"not-found\"}}");
        }
    }

    private String runObject() {
        return "{\"data\":{\"id\":\"" + RUN_ID + "\",\"status\":\"" + runStatus
                + "\",\"defaultDatasetId\":\"" + DATASET_ID
                + "\",\"defaultKeyValueStoreId\":\"" + KEY_VALUE_STORE_ID + "\"}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public record Request(String method, String path, String query, String authorization, String body) {
    }
}

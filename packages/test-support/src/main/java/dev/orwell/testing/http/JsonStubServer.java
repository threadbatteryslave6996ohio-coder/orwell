package dev.orwell.testing.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A real HTTP server on a loopback port, for a test whose subject is what a client puts on the
 * wire: the URL it builds, the headers it sets, the body it serializes, and what it makes of the
 * answer. A mocked {@code HttpClient} tests none of those.
 *
 * <pre>{@code
 * try (JsonStubServer auth = JsonStubServer.start()) {
 *     AtomicReference<String> sent = auth.stub("/def/login", 200, """
 *             {"clientId":"client-a","token":"token-456"}
 *             """);
 *
 *     String token = new ClientAuthSession(auth.baseUrl(), "client-a", "secret", null).token();
 *
 *     assertEquals("token-456", token);
 *     assertTrue(sent.get().contains("\"clientId\":\"client-a\""));
 * }
 * }</pre>
 *
 * <p>Port 0, so nothing here can collide with the repo's real local stack or with a parallel test.
 * {@code com.sun.net.httpserver} is in the JDK, which is what lets this module carry no
 * dependencies and so be usable from a desktop client's tests as readily as a server's.
 *
 * <p>Not thread-safe to configure, and it does not need to be: register the paths, then make the
 * call. The server itself serves concurrently, so a handler that records something should record
 * it somewhere thread-safe — {@link #stub} uses an {@link AtomicReference} for exactly that.
 */
public final class JsonStubServer implements AutoCloseable {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final HttpServer server;

    private JsonStubServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a server on a free loopback port. Register paths with {@link #on} or {@link #stub}.
     *
     * <p>Bound to the loopback <em>address</em>, not to the name {@code localhost}: on a dual-stack
     * host that name can resolve to {@code ::1}, and a test that then connects to {@code 127.0.0.1}
     * is refused. {@link #baseUrl()} reports whichever address this actually bound, so the two
     * cannot disagree.
     */
    public static JsonStubServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.start();
        return new JsonStubServer(server);
    }

    /**
     * Handles {@code path} and everything under it. Safe to call after {@link #start()} — the JDK
     * server takes new contexts while it is running, which is what lets a test read as
     * arrange-then-act rather than building the whole server up front.
     */
    public JsonStubServer on(String path, HttpHandler handler) {
        server.createContext(path, handler);
        return this;
    }

    /**
     * Answers {@code path} with a fixed status and body, and captures the request body each call
     * sent — the shape almost every one of these tests wants.
     *
     * @return the last body received, or null until the path is called
     */
    public AtomicReference<String> stub(String path, int status, String body) {
        AtomicReference<String> received = new AtomicReference<>();
        on(path, exchange -> {
            received.set(readBody(exchange));
            respond(exchange, status, body);
        });
        return received;
    }

    /** Where to point the client under test, on whichever loopback address {@link #start()} bound. */
    public String baseUrl() {
        String host = LOOPBACK.getHostAddress();
        // An IPv6 literal has to be bracketed in a URL; an IPv4 one must not be.
        return "http://%s:%d".formatted(host.contains(":") ? "[" + host + "]" : host, port());
    }

    /** The port, for a test that has to build the URL itself. */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Answers with a JSON body. Always sets {@code Content-Type}, and always closes the exchange —
     * two things the hand-rolled copies of this disagreed about, and a client that parses by
     * content type behaves differently depending on which copy it met.
     */
    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}

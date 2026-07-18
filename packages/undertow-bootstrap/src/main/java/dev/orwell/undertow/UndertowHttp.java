package dev.orwell.undertow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.http.EndpointResponse;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RequestTooBigException;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.Headers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Small shared surface for embedded Undertow services. */
public final class UndertowHttp {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private UndertowHttp() {
    }

    public static RoutingHandler routes() {
        return Handlers.routing();
    }

    public static Undertow start(String host, int port, HttpHandler handler) {
        Undertow server = Undertow.builder()
                .addHttpListener(port, host)
                .setIoThreads(1)
                .setWorkerThreads(5)
                .setHandler(new BlockingHandler(handler))
                .build();
        server.start();
        return server;
    }

    /** Starts the server and keeps the process alive until it receives a shutdown signal. */
    public static void startAndWait(String host, int port, HttpHandler handler) throws InterruptedException {
        Undertow server = start(host, port, handler);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            stopped.countDown();
        }, "undertow-shutdown"));
        stopped.await();
    }

    public static Map<String, Object> readObject(HttpServerExchange exchange, long maxBytes)
            throws IOException, RequestBodyException {
        exchange.setMaxEntitySize(maxBytes);
        exchange.startBlocking();
        return readObject(exchange.getInputStream());
    }

    static Map<String, Object> readObject(InputStream input) throws IOException, RequestBodyException {
        JsonNode body;
        try {
            body = JSON.readTree(input);
        } catch (JsonProcessingException exception) {
            throw new RequestBodyException("invalid json", exception);
        } catch (RequestTooBigException exception) {
            throw new RequestBodyException(413, "request body too large", exception);
        }

        if (body == null || body.isMissingNode()) {
            throw new RequestBodyException("missing request body");
        }
        if (!body.isObject()) {
            throw new RequestBodyException("invalid request body");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> object = JSON.convertValue(body, Map.class);
        return object;
    }

    public static void sendJson(HttpServerExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(body.length));
        exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }

    public static void send(HttpServerExchange exchange, EndpointResponse<?> response) throws IOException {
        sendJson(exchange, response.status(), response.body());
    }

    /**
     * Adapts a JSON-object endpoint and applies the shared body-error contract, including 413 for
     * requests larger than {@code maxBytes}.
     */
    public static HttpHandler jsonObject(long maxBytes, JsonObjectEndpoint endpoint) {
        return exchange -> {
            try {
                send(exchange, endpoint.handle(readObject(exchange, maxBytes)));
            } catch (RequestBodyException exception) {
                sendError(exchange, exception.statusCode(), exception.getMessage());
            }
        };
    }

    public static void sendError(HttpServerExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("success", false, "error", message));
    }

    public static Map<String, Object> health(Map<String, ?> details) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("success", true);
        health.put("status", "healthy");
        health.putAll(details);
        health.put("engine", "undertow");
        return health;
    }

    /** A client-safe request-body classification shared by all Undertow services. */
    public static final class RequestBodyException extends Exception {
        private final int statusCode;

        private RequestBodyException(String message) {
            this(400, message, null);
        }

        private RequestBodyException(String message, Throwable cause) {
            this(400, message, cause);
        }

        private RequestBodyException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    @FunctionalInterface
    public interface JsonObjectEndpoint {
        EndpointResponse<?> handle(Map<String, Object> body) throws Exception;
    }
}

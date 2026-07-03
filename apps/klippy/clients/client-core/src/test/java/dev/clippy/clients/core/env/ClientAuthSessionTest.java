package dev.clippy.clients.core.env;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAuthSessionTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsExistingTokenWithoutRefreshing() {
        ClientAuthSession session = new ClientAuthSession(null, "client-a", null, "token-123");

        assertEquals("token-123", session.token());
        assertTrue(session.hasToken());
        assertFalse(session.canRefresh());
    }

    @Test
    void refreshesTokenFromAuthServerWhenNeeded() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"clientId":"client-a","token":"token-456"}
                    """);
        });
        server.start();

        ClientAuthSession session = new ClientAuthSession(baseUrl(), "client-a", "super-secret", null);

        assertEquals("token-456", session.token());
        assertTrue(body.get().contains("\"clientId\":\"client-a\""));
        assertTrue(body.get().contains("\"secret\":\"super-secret\""));
    }

    @Test
    void rejectsMissingTokenWhenRefreshIsImpossible() {
        ClientAuthSession session = new ClientAuthSession(null, "client-a", null, null);

        IllegalStateException exception = assertThrows(IllegalStateException.class, session::token);

        assertTrue(exception.getMessage().contains("CLIENT_TOKEN is required"));
    }

    private String baseUrl() {
        return "http://localhost:%d".formatted(server.getAddress().getPort());
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}

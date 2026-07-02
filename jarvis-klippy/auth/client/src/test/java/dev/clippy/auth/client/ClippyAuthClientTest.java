package dev.clippy.auth.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.clippy.auth.api.LoginResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClippyAuthClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loginPostsCredentialsAndParsesTheResponse() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"clientId":"client-a","token":"token-123"}
                    """);
        });
        server.start();

        ClippyAuthClient client = new ClippyAuthClient(baseUrl());
        LoginResponse response = client.login("client-a", "super-secret");

        assertEquals(new LoginResponse("client-a", "token-123"), response);
        assertEquals("POST", method.get());
        assertTrue(body.get().contains("\"clientId\":\"client-a\""));
        assertTrue(body.get().contains("\"secret\":\"super-secret\""));
    }

    @Test
    void isTokenValidForClientPostsToCheckEndpoint() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tokens/check", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"valid":true,"clientId":"client-a"}
                    """);
        });
        server.start();

        ClippyAuthClient client = new ClippyAuthClient(baseUrl());

        assertTrue(client.isTokenValidForClient("client-a", "token-123"));
        assertEquals("POST", method.get());
        assertTrue(body.get().contains("\"clientId\":\"client-a\""));
        assertTrue(body.get().contains("\"token\":\"token-123\""));
    }

    @Test
    void loginWrapsServerErrors() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> respond(exchange, 503, "unavailable"));
        server.start();

        ClippyAuthClient client = new ClippyAuthClient(baseUrl());

        AuthClientException exception = assertThrows(AuthClientException.class,
                () -> client.login("client-a", "super-secret"));

        assertTrue(exception.getMessage().contains("503"));
    }

    @Test
    void tokenCheckReturnsFalseWhenResponseDoesNotMatchClientId() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tokens/check", exchange -> respond(exchange, 200, """
                {"valid":true,"clientId":"different-client"}
                """));
        server.start();

        ClippyAuthClient client = new ClippyAuthClient(baseUrl());

        assertFalse(client.isTokenValidForClient("client-a", "token-123"));
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

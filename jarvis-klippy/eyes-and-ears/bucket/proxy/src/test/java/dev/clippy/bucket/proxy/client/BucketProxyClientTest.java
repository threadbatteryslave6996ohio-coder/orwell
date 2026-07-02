package dev.clippy.bucket.proxy.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketProxyClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loginPostsJsonAndParsesResponse() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"success":true,"clientId":"client-a","token":"token-123","tokenType":"Bearer"}
                    """);
        });
        server.start();

        BucketProxyClient client = new BucketProxyClient(baseUrl());
        BucketProxyClient.LoginResponse response = client.login("client-a", "secret");

        assertTrue(response.success());
        assertEquals("client-a", response.clientId());
        assertEquals("POST", method.get());
        assertTrue(body.get().contains("\"username\":\"client-a\""));
        assertTrue(body.get().contains("\"password\":\"secret\""));
    }

    @Test
    void uploadSendsMultipartWithAuthHeaders() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> clientId = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/upload", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            clientId.set(exchange.getRequestHeaders().getFirst("X-Client-Id"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"success":true,"message":"ok","key":"uploads/file.txt","etag":"etag-1"}
                    """);
        });
        server.start();

        Path file = Files.createTempFile("proxy-client-", ".txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        try {
            BucketProxyClient client = new BucketProxyClient(baseUrl());
            BucketProxyClient.UploadResponse response = client.upload("client-a", "token-123", file, "uploads", "file.txt");

            assertTrue(response.success());
            assertEquals("Bearer token-123", auth.get());
            assertEquals("client-a", clientId.get());
            assertTrue(contentType.get().startsWith("multipart/form-data"));
            assertTrue(body.get().contains("name=\"folder\""));
            assertTrue(body.get().contains("uploads"));
            assertTrue(body.get().contains("name=\"fileName\""));
            assertTrue(body.get().contains("file.txt"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void listParsesNestedFileRecords() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/list/uploads", exchange -> respond(exchange, 200, """
                {"success":true,"folder":"uploads","files":[{"key":"uploads/a.txt","size":12,"lastModified":"2026-07-02T11:00:00Z"}]}
                """));
        server.start();

        BucketProxyClient client = new BucketProxyClient(baseUrl());
        BucketProxyClient.ListResponse response = client.list("client-a", "token-123", "uploads");

        assertTrue(response.success());
        assertEquals(1, response.files().size());
        assertEquals("uploads/a.txt", response.files().getFirst().key());
        assertEquals(Instant.parse("2026-07-02T11:00:00Z"), response.files().getFirst().lastModified());
    }

    @Test
    void deleteEncodesSlashSeparatedKey() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/delete/folder/object.txt", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                    {"success":true,"message":"deleted","key":"folder/object.txt"}
                    """);
        });
        server.start();

        BucketProxyClient client = new BucketProxyClient(baseUrl());
        BucketProxyClient.DeleteResponse response = client.delete("client-a", "token-123", "folder/object.txt");

        assertTrue(response.success());
        assertEquals("/delete/folder/object.txt", path.get());
    }

    @Test
    void loginExposesHttpStatusOnFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> respond(exchange, 401, """
                {"success":false,"error":"Invalid username or password"}
                """));
        server.start();

        BucketProxyClient client = new BucketProxyClient(baseUrl());
        BucketProxyClientException exception = assertThrows(BucketProxyClientException.class,
                () -> client.login("client-a", "wrong"));

        assertEquals(401, exception.statusCode());
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

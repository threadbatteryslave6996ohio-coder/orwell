package dev.orwell.objectstorage.proxy.client;

import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static dev.orwell.testing.http.JsonStubServer.readBody;
import static dev.orwell.testing.http.JsonStubServer.respond;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStorageProxyClientTest {
    private JsonStubServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void loginPostsJsonAndParsesResponse() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/login", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"success":true,"clientId":"client-a","token":"token-123","tokenType":"Bearer"}
                    """);
        });

        ObjectStorageProxyClient client = new ObjectStorageProxyClient(server.baseUrl());
        ObjectStorageProxyClient.LoginResponse response = client.login("client-a", "secret");

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
        server = JsonStubServer.start();
        server.on("/upload", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            clientId.set(exchange.getRequestHeaders().getFirst("X-Client-Id"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"success":true,"message":"ok","key":"uploads/file.txt","etag":"etag-1"}
                    """);
        });

        Path file = Files.createTempFile("proxy-client-", ".txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        try {
            ObjectStorageProxyClient client = new ObjectStorageProxyClient(server.baseUrl());
            ObjectStorageProxyClient.UploadResponse response = client.upload("client-a", "token-123", file, "uploads", "file.txt");

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
        server = JsonStubServer.start();
        server.on("/list/uploads", exchange -> respond(exchange, 200, """
                {"success":true,"folder":"uploads","files":[{"key":"uploads/a.txt","size":12,"lastModified":"2026-07-02T11:00:00Z"}]}
                """));

        ObjectStorageProxyClient client = new ObjectStorageProxyClient(server.baseUrl());
        ObjectStorageProxyClient.ListResponse response = client.list("client-a", "token-123", "uploads");

        assertTrue(response.success());
        assertEquals(1, response.files().size());
        assertEquals("uploads/a.txt", response.files().getFirst().key());
        assertEquals(Instant.parse("2026-07-02T11:00:00Z"), response.files().getFirst().lastModified());
    }

    @Test
    void deleteEncodesSlashSeparatedKey() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/delete/folder/object.txt", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                    {"success":true,"message":"deleted","key":"folder/object.txt"}
                    """);
        });

        ObjectStorageProxyClient client = new ObjectStorageProxyClient(server.baseUrl());
        ObjectStorageProxyClient.DeleteResponse response = client.delete("client-a", "token-123", "folder/object.txt");

        assertTrue(response.success());
        assertEquals("/delete/folder/object.txt", path.get());
    }

    @Test
    void loginExposesHttpStatusOnFailure() throws IOException {
        server = JsonStubServer.start();
        server.on("/login", exchange -> respond(exchange, 401, """
                {"success":false,"error":"Invalid username or password"}
                """));

        ObjectStorageProxyClient client = new ObjectStorageProxyClient(server.baseUrl());
        ObjectStorageProxyClientException exception = assertThrows(ObjectStorageProxyClientException.class,
                () -> client.login("client-a", "wrong"));

        assertEquals(401, exception.statusCode());
    }



}

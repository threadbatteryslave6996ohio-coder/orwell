package dev.orwell.clients.core;

import dev.orwell.testing.http.JsonStubServer;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import dev.orwell.logging.Logger;

class ClipboardApiClientTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    @Test
    void reportsAnUnobtainableTokenAsAnAuthenticationFailure() {
        ClipboardApiClient client = new ClipboardApiClient(
                URI.create("http://127.0.0.1:1/clipboard"),
                new ClientAuthSession(null, "client-a", null, null),
                Duration.ofMillis(100),
                NO_OP_LOGGER);

        assertThrows(HttpAuthenticationException.class, () -> client.create(
                new ClipboardEntry("client-a", "text", Instant.parse("2026-06-27T15:30:45Z"))));
    }

    @Test
    void postsSerializedEntryWithBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> clientId = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        JsonStubServer server = JsonStubServer.start();
        server.on("/clipboard", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            clientId.set(exchange.getRequestHeaders().getFirst("X-Client-Id"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        try {
            URI endpoint = URI.create(server.baseUrl() + "/clipboard");
            ClipboardApiClient client = new ClipboardApiClient(
                    endpoint, new ClientAuthSession(null, "client-a", null, "token-a"), Duration.ofSeconds(2),
                    NO_OP_LOGGER);

            var response = client.create(new ClipboardEntry(
                    "client-a", "line one\nline two", Instant.parse("2026-06-27T15:30:45Z")));

            assertEquals(201, response.statusCode());
            assertEquals("Bearer token-a", authorization.get());
            assertEquals("client-a", clientId.get());
            assertEquals("line one\nline two",
                    ClipboardJson.mapper().readTree(requestBody.get()).get("content").textValue());
            assertEquals("2026-06-27T15:30:45Z",
                    ClipboardJson.mapper().readTree(requestBody.get()).get("timestamp").textValue());
        } finally {
            server.close();
        }
    }

    @Test
    void completesTheRefreshRetryWhenTheAuditListenerThrows() throws Exception {
        AtomicInteger clipboardRequests = new AtomicInteger();
        JsonStubServer server = JsonStubServer.start();
        server.on("/def/login", exchange -> {
            byte[] response = "{\"clientId\":\"client-a\",\"token\":\"fresh-token\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.on("/clipboard", exchange -> {
            clipboardRequests.incrementAndGet();
            boolean refreshed = "Bearer fresh-token".equals(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(refreshed ? 201 : 401, -1);
            exchange.close();
        });

        try {
            String baseUrl = server.baseUrl();
            // Stands in for AuthAuditLogger with a broken sink: every callback fails.
            ClipboardApiClient client = new ClipboardApiClient(
                    java.net.http.HttpClient.newHttpClient(),
                    URI.create(baseUrl + "/clipboard"),
                    new ClientAuthSession(baseUrl, "client-a", "secret-value", "expired-token"),
                    Duration.ofSeconds(2),
                    new ClipboardApiClient.AuthRefreshListener() {
                        @Override
                        public void beforeRefresh() {
                            throw new IllegalStateException("audit sink is down");
                        }

                        @Override
                        public void afterRefresh() {
                            throw new IllegalStateException("audit sink is down");
                        }
                    });

            var response = client.create(new ClipboardEntry("client-a", "content", Instant.EPOCH));

            assertEquals(201, response.statusCode());
            assertEquals(2, clipboardRequests.get());
        } finally {
            server.close();
        }
    }

    @Test
    void refreshesOnceAndRetriesAfterUnauthorizedResponse() throws Exception {
        AtomicInteger clipboardRequests = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        JsonStubServer server = JsonStubServer.start();
        server.on("/def/login", exchange -> {
            byte[] response = "{\"clientId\":\"client-a\",\"token\":\"fresh-token\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.on("/clipboard", exchange -> {
            clipboardRequests.incrementAndGet();
            boolean refreshed = "Bearer fresh-token".equals(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(refreshed ? 201 : 401, -1);
            exchange.close();
        });

        try {
            String baseUrl = server.baseUrl();
            ClientAuthSession auth = new ClientAuthSession(baseUrl, "client-a", "secret-value", "expired-token");
            ClipboardApiClient client = new ClipboardApiClient(
                    java.net.http.HttpClient.newHttpClient(),
                    URI.create(baseUrl + "/clipboard"),
                    auth,
                    Duration.ofSeconds(2),
                    new ClipboardApiClient.AuthRefreshListener() {
                        @Override
                        public void afterRefresh() {
                            refreshes.incrementAndGet();
                        }
                    });

            var response = client.create(new ClipboardEntry("client-a", "content", Instant.EPOCH));

            assertEquals(201, response.statusCode());
            assertEquals(2, clipboardRequests.get());
            assertEquals(1, refreshes.get());
            assertEquals("fresh-token", auth.token());
        } finally {
            server.close();
        }
    }
}

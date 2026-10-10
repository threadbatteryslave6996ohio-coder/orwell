package dev.orwell.auth.http.client;

import dev.orwell.auth.http.api.CreateIdentityHttpResponse;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static dev.orwell.testing.http.JsonStubServer.readBody;
import static dev.orwell.testing.http.JsonStubServer.respond;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpAuthenticationStrategyTest {
    private JsonStubServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void loginPostsCredentialsAndParsesTheResponse() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/def/login", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"clientId":"client-a","token":"token-123"}
                    """);
        });

        HttpAuthenticationStrategy client = new HttpAuthenticationStrategy(server.baseUrl());
        LoginHttpResponse response = client.login("client-a", "super-secret");

        assertEquals(new LoginHttpResponse("client-a", "token-123"), response);
        assertEquals("POST", method.get());
        assertTrue(body.get().contains("\"clientId\":\"client-a\""));
        assertTrue(body.get().contains("\"secret\":\"super-secret\""));
    }

    @Test
    void isTokenValidForClientPostsToCheckEndpoint() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/def/tokens/check", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(readBody(exchange));
            respond(exchange, 200, """
                    {"valid":true,"clientId":"client-a"}
                    """);
        });

        HttpAuthenticationStrategy client = new HttpAuthenticationStrategy(server.baseUrl());

        assertTrue(client.isTokenValidForClient("client-a", "token-123"));
        assertEquals("POST", method.get());
        assertTrue(body.get().contains("\"clientId\":\"client-a\""));
        assertTrue(body.get().contains("\"token\":\"token-123\""));
    }

    @Test
    void loginWrapsServerErrors() throws IOException {
        server = JsonStubServer.start();
        server.on("/def/login", exchange -> respond(exchange, 503, "unavailable"));

        HttpAuthenticationStrategy client = new HttpAuthenticationStrategy(server.baseUrl());

        HttpAuthenticationException exception = assertThrows(HttpAuthenticationException.class,
                () -> client.login("client-a", "super-secret"));

        assertTrue(exception.getMessage().contains("503"));
    }

    @Test
    void tokenCheckReturnsFalseWhenResponseDoesNotMatchClientId() throws IOException {
        server = JsonStubServer.start();
        server.on("/def/tokens/check", exchange -> respond(exchange, 200, """
                {"valid":true,"clientId":"different-client"}
                """));

        HttpAuthenticationStrategy client = new HttpAuthenticationStrategy(server.baseUrl());

        assertFalse(client.isTokenValidForClient("client-a", "token-123"));
    }

    @Test
    void everyCallGoesThroughTheConfiguredWorkspace() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/team-a/login", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, """
                    {"clientId":"client-a","token":"token-123"}
                    """);
        });

        new HttpAuthenticationStrategy(server.baseUrl(), "team-a").login("client-a", "super-secret");

        assertEquals("/team-a/login", path.get());
    }

    @Test
    void createIdentityPresentsTheAdminCredentialAndParsesTheResponse() throws IOException {
        AtomicReference<String> clientIdHeader = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/team-a/identities", exchange -> {
            clientIdHeader.set(exchange.getRequestHeaders().getFirst("X-Client-Id"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            respond(exchange, 201, """
                    {"workspace":"team-a","clientId":"client-a","createdAt":"2026-06-26T00:00:00Z"}
                    """);
        });

        CreateIdentityHttpResponse response = new HttpAuthenticationStrategy(server.baseUrl(), "team-a")
                .createIdentity("ops-admin", "admin-token", "client-a", "super-secret");

        assertEquals("team-a", response.workspace());
        assertEquals("client-a", response.clientId());
        assertEquals(Instant.parse("2026-06-26T00:00:00Z"), response.createdAt());
        assertEquals("ops-admin", clientIdHeader.get());
        assertEquals("Bearer admin-token", authorization.get());
        assertTrue(body.get().contains("\"secret\":\"super-secret\""));
    }

    /** Caught at construction rather than as a 400 per call: the name cannot become valid later. */
    @Test
    void refusesAWorkspaceNameTheServerWouldReject() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpAuthenticationStrategy("http://localhost:1", "Team A"));
    }
}

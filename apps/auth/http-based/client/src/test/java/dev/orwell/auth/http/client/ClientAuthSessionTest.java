package dev.orwell.auth.http.client;

import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAuthSessionTest {
    private JsonStubServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
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
        server = JsonStubServer.start();
        AtomicReference<String> body = server.stub("/def/login", 200, """
                {"clientId":"client-a","token":"token-456"}
                """);

        ClientAuthSession session = new ClientAuthSession(server.baseUrl(), "client-a", "super-secret", null);

        assertEquals("token-456", session.token());
        assertTrue(body.get().contains("\"clientId\":\"client-a\""));
        assertTrue(body.get().contains("\"secret\":\"super-secret\""));
    }

    @Test
    void reportsALoginResponseWithoutATokenAsAnAuthenticationFailure() throws IOException {
        server = JsonStubServer.start();
        // A 200 that omits "token" entirely. LoginHttpResponse.token is a nullable record
        // component, so this parses cleanly and only fails when the value is used.
        server.stub("/def/login", 200, """
                {"clientId":"client-a"}
                """);

        ClientAuthSession session = new ClientAuthSession(server.baseUrl(), "client-a", "super-secret", null);

        assertThrows(HttpAuthenticationException.class, session::refresh);
    }

    @Test
    void reportsAnEmptyLoginResponseBodyAsAnAuthenticationFailure() throws IOException {
        server = JsonStubServer.start();
        server.stub("/def/login", 200, "");

        ClientAuthSession session = new ClientAuthSession(server.baseUrl(), "client-a", "super-secret", null);

        assertThrows(HttpAuthenticationException.class, session::refresh);
    }

    @Test
    void rejectsMissingTokenWhenRefreshIsImpossible() {
        ClientAuthSession session = new ClientAuthSession(null, "client-a", null, null);

        IllegalStateException exception = assertThrows(IllegalStateException.class, session::token);

        assertTrue(exception.getMessage().contains("An initial token is required"));
    }
}

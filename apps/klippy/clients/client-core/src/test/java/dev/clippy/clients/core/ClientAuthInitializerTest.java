package dev.clippy.clients.core;

import dev.clippy.clients.core.env.ClientAuthSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAuthInitializerTest {
    @Test
    void acceptsStaticToken() {
        ClientAuthSession authSession = new ClientAuthSession(null, "client-a", null, "token-a");

        assertDoesNotThrow(() -> ClientAuthInitializer.initialize(authSession, null, "client-a"));
    }

    @Test
    void rejectsMissingCredentials() {
        ClientAuthSession authSession = new ClientAuthSession(null, "client-a", null, null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ClientAuthInitializer.initialize(authSession, null, "client-a"));

        assertEquals("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.", exception.getMessage());
    }

    @Test
    void rejectsRefreshWithoutAuthServer() {
        ClientAuthSession authSession = new ClientAuthSession(null, "client-a", "secret-a", null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ClientAuthInitializer.initialize(authSession, null, "client-a"));

        assertEquals("AUTH_SERVER_URL is required when CLIENT_SECRET is set.", exception.getMessage());
    }
}

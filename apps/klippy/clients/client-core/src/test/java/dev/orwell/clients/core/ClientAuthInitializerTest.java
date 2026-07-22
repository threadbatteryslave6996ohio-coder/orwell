package dev.orwell.clients.core;

import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAuthInitializerTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    @Test
    void acceptsStaticToken() {
        ClientAuthSession authSession = new ClientAuthSession(null, "client-a", null, "token-a");

        assertDoesNotThrow(() -> ClientAuthInitializer.initialize(authSession, null, "client-a", NO_OP_LOGGER));
    }

    @Test
    void rejectsMissingCredentials() {
        ClientAuthSession authSession = new ClientAuthSession(null, "client-a", null, null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ClientAuthInitializer.initialize(authSession, null, "client-a", NO_OP_LOGGER));

        assertEquals("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.", exception.getMessage());
    }

    @Test
    void rejectsRefreshWithoutAuthServer() {
        ClientAuthSession authSession = new ClientAuthSession(null, "client-a", "secret-a", null);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> ClientAuthInitializer.initialize(authSession, null, "client-a", NO_OP_LOGGER));

        assertEquals("AUTH_SERVER_URL is required when CLIENT_SECRET is set.", exception.getMessage());
    }
}

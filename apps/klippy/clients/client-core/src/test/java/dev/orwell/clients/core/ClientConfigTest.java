package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.orwell.logging.Logger;

class ClientConfigTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };
    @Test
    void loadsEndpointAuthAndDefaultClientId() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_TOKEN", "token"
        ));

        ClientConfig config = ClientConfig.load(env, "client-host", NO_OP_LOGGER);

        assertEquals(URI.create("http://localhost:8080/clipboard"), config.endpoint());
        assertEquals("client-host", config.clientId());
        assertEquals("token", config.clientToken());
        assertTrue(config.authSession().hasToken());
        assertFalse(config.authSession().canRefresh());
    }

    @Test
    void configuredClientIdTakesPrecedence() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "configured",
                "CLIENT_TOKEN", "token"
        ));

        ClientConfig config = ClientConfig.load(env, "fallback", NO_OP_LOGGER);

        assertEquals("configured", config.clientId());
    }
}

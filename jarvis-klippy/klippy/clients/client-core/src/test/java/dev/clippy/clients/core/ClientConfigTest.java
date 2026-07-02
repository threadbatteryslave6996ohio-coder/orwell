package dev.clippy.clients.core;

import dev.clippy.clients.core.env.ClientEnvs;
import dev.clippy.utils.envmanager.Env;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTest {
    @Test
    void loadsEndpointAuthAndDefaultClientId() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_TOKEN", "token"
        ));

        ClientConfig config = ClientConfig.load(env, "client-host");

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

        ClientConfig config = ClientConfig.load(env, "fallback");

        assertEquals("configured", config.clientId());
    }
}

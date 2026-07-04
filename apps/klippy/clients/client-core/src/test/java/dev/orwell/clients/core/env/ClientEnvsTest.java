package dev.orwell.clients.core.env;

import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientEnvsTest {
    @Test
    void parsesExpectedClientEnvironmentValues() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "client-a",
                "CLIENT_TOKEN", "token-123",
                "CLIENT_SECRET", "secret-123",
                "AUTH_SERVER_URL", "http://localhost:8081",
                "CLIPBOARD_POLL_INTERVAL_MS", "250",
                "CLIPBOARD_BACKEND", "xclip",
                "OFFLINE_FILE_LOCKER_SOCKET", "/tmp/clippy-test.sock",
                "OFFLINE_SYNC_INTERVAL_MINUTES", "15"
        ));

        assertEquals("http://localhost:8080", env.get(ClientEnvs.REMOTE_SERVER_URL));
        assertEquals("client-a", env.get(ClientEnvs.CLIENT_ID));
        assertEquals("token-123", env.get(ClientEnvs.CLIENT_TOKEN));
        assertEquals("secret-123", env.get(ClientEnvs.CLIENT_SECRET));
        assertEquals("http://localhost:8081", env.get(ClientEnvs.AUTH_SERVER_URL));
        assertEquals(250L, env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS));
        assertEquals("xclip", env.get(ClientEnvs.CLIPBOARD_BACKEND));
        assertEquals("/tmp/clippy-test.sock", env.get(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET));
        assertEquals(15L, env.get(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES));
        assertTrue(env.has(ClientEnvs.CLIENT_ID));
    }
}

package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopClientRunnerTest {
    @Test
    void buildsMacStyleStartupFields() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "mac-host",
                "CLIENT_TOKEN", "token"
        ));
        ClientConfig config = ClientConfig.load(env, "fallback");

        Map<String, Object> fields = DesktopClientRunner.startupFields(config, 1L, Map.of());

        // Key set, not key order: LogFormatter sorts metadata, so insertion order never
        // reaches a rendered log line and asserting on it would protect nothing.
        assertEquals(
                Set.of("clientId", "endpoint", "authServer", "pollIntervalMs", "tokenSource"),
                fields.keySet());
        assertEquals("mac-host", fields.get("clientId"));
        assertEquals("http://localhost:8080/clipboard", fields.get("endpoint"));
        assertEquals("unset", fields.get("authServer"));
        assertEquals("1", fields.get("pollIntervalMs"));
        assertEquals("CLIENT_TOKEN", fields.get("tokenSource"));
    }

    @Test
    void insertsExtraFieldsBeforeTokenSourceAndReportsRefreshSource() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "linux-host",
                "AUTH_SERVER_URL", "http://localhost:9090",
                "CLIENT_SECRET", "secret"
        ));
        ClientConfig config = ClientConfig.load(env, "fallback");

        Map<String, Object> fields =
                DesktopClientRunner.startupFields(config, 1000L, Map.of("clipboardBackend", "awt"));

        assertEquals(
                Set.of("clientId", "endpoint", "authServer", "pollIntervalMs", "clipboardBackend", "tokenSource"),
                fields.keySet());
        assertEquals("linux-host", fields.get("clientId"));
        assertEquals("http://localhost:8080/clipboard", fields.get("endpoint"));
        assertEquals("http://localhost:9090", fields.get("authServer"));
        assertEquals("1000", fields.get("pollIntervalMs"));
        assertEquals("awt", fields.get("clipboardBackend"));
        assertEquals("CLIENT_SECRET", fields.get("tokenSource"));
    }
}

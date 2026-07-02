package dev.clippy.clients.sync;

import dev.clippy.clients.core.env.ClientEnvs;
import dev.clippy.utils.envmanager.Env;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineSyncConfigTest {
    @Test
    void appliesDefaultsWhenOnlyRequiredEnvIsSet() {
        Env env = ClientEnvs.from(Map.of("REMOTE_SERVER_URL", "http://localhost:8080"));

        OfflineSyncConfig config = OfflineSyncConfig.load(env, Path.of("offline.json"));

        assertEquals(URI.create("http://localhost:8080/clipboard"), config.endpoint());
        assertEquals(SyncMonitor.DEFAULT_SYNC_INTERVAL, config.syncInterval());
        assertFalse(config.clientIdConfigured());
        assertNull(config.configuredClientId());
        assertNull(config.authServerUrl());
        assertNull(config.clientSecret());
        assertNull(config.clientToken());
    }

    @Test
    void parsesConfiguredClientIdAuthAndSyncInterval() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "configured",
                "AUTH_SERVER_URL", "http://localhost:9090",
                "CLIENT_SECRET", "secret",
                "OFFLINE_SYNC_INTERVAL_MINUTES", "15"
        ));

        OfflineSyncConfig config = OfflineSyncConfig.load(env, Path.of("offline.json"));

        assertTrue(config.clientIdConfigured());
        assertEquals("configured", config.configuredClientId());
        assertEquals("http://localhost:9090", config.authServerUrl());
        assertEquals("secret", config.clientSecret());
        assertEquals(Duration.ofMinutes(15), config.syncInterval());
    }

    @Test
    void rejectsSyncIntervalBelowOneMinute() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "OFFLINE_SYNC_INTERVAL_MINUTES", "0"
        ));

        assertThrows(IllegalArgumentException.class, () -> OfflineSyncConfig.load(env, Path.of("offline.json")));
    }

    @Test
    void derivesDeadLetterPathAlongsideOfflineLog() {
        Env env = ClientEnvs.from(Map.of("REMOTE_SERVER_URL", "http://localhost:8080"));

        OfflineSyncConfig config = OfflineSyncConfig.load(env, Path.of("/data/clippy-offline-clipboard.json"));

        assertEquals(
                Path.of("/data/clippy-offline-clipboard-dead-letter.json"),
                config.deadLetterLog());
    }
}

package dev.orwell.clients.core;

import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.logging.LogEntry;
import dev.orwell.logging.LogLevel;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatSchedulerTest {
    private final List<LogEntry> entries = new ArrayList<>();
    private final Logger logger = entries::add;

    private ClipboardApiClient apiClient() {
        ClientAuthSession session = new ClientAuthSession("http://localhost:8081", "client-1", null, "token");
        return new ClipboardApiClient(URI.create("http://localhost:8080/clipboard"), session, Duration.ofSeconds(1));
    }

    @Test
    void clampsSubMinimumIntervalWithWarningInsteadOfCrashing() {
        new HeartbeatScheduler(apiClient(), URI.create("http://localhost:8080/heartbeat"), "client-1", 0L, logger);

        assertTrue(entries.stream().anyMatch(entry ->
                entry.level() == LogLevel.WARN && entry.message().contains("below minimum")));
    }

    @Test
    void acceptsAValidIntervalWithoutWarning() {
        new HeartbeatScheduler(apiClient(), URI.create("http://localhost:8080/heartbeat"), "client-1", 5000L, logger);

        assertFalse(entries.stream().anyMatch(entry -> entry.level() == LogLevel.WARN));
    }
}

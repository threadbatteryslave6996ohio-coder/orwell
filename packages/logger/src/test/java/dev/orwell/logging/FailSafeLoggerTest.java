package dev.orwell.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FailSafeLoggerTest {
    @Test
    void aBrokenSinkNeverFailsTheCaller() {
        Logger broken = entry -> {
            throw new IllegalStateException("Cannot write custom logger output.");
        };

        // The auth server logs unguarded inside /login; this is what keeps a full disk
        // from turning a valid login into an HTTP 500.
        assertDoesNotThrow(() -> new FailSafeLogger(broken).info("Login request received."));
    }

    @Test
    void aHealthySinkStillReceivesEveryEntry() {
        List<LogEntry> received = new ArrayList<>();

        FailSafeLogger logger = new FailSafeLogger(received::add);
        logger.info("first");
        logger.error("second");

        assertEquals(2, received.size());
        assertEquals(LogLevel.ERROR, received.get(1).level());
    }
}

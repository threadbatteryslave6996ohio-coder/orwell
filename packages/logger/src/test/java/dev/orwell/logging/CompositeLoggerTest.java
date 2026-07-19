package dev.orwell.logging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeLoggerTest {
    @Test
    void fansTheEntryOutToEverySink() {
        List<LogEntry> first = new ArrayList<>();
        List<LogEntry> second = new ArrayList<>();

        new CompositeLogger(first::add, second::add).info("Startup complete");

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals("Startup complete", first.getFirst().message());
        assertEquals(LogLevel.INFO, second.getFirst().level());
    }

    @Test
    void aFailingSinkStillLetsTheOthersReceiveTheEntry() {
        List<LogEntry> healthy = new ArrayList<>();
        Logger broken = entry -> {
            throw new IllegalStateException("disk full");
        };

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CompositeLogger(broken, healthy::add).error("Delivery failed"));

        assertEquals("disk full", failure.getMessage());
        assertEquals(1, healthy.size(), "a broken sink must not swallow the entry for the others");
    }

    @Test
    void reportsEverySinkFailureRatherThanOnlyTheFirst() {
        Logger firstBroken = entry -> {
            throw new IllegalStateException("disk full");
        };
        Logger secondBroken = entry -> {
            throw new IllegalStateException("socket closed");
        };

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CompositeLogger(firstBroken, secondBroken).info("Anything"));

        assertEquals("disk full", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("socket closed"));
    }
}

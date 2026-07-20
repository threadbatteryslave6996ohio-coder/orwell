package dev.orwell.logging;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogEntryTest {
    @Test
    void nullMetadataDefaultsToEmptyMap() {
        LogEntry entry = new LogEntry(LogLevel.INFO, "test", null);
        assertTrue(entry.metadata().isEmpty());
    }

    @Test
    void metadataIsDefensivelyCopied() {
        Map<String, Object> original = new java.util.HashMap<>();
        original.put("key", "value");
        LogEntry entry = new LogEntry(LogLevel.INFO, "test", original);
        original.put("key", "modified");
        assertEquals("value", entry.metadata().get("key"));
    }

    @Test
    void metadataIsUnmodifiable() {
        LogEntry entry = new LogEntry(LogLevel.INFO, "test", Map.of("key", "value"));
        assertThrows(UnsupportedOperationException.class, () -> entry.metadata().put("newKey", "newValue"));
    }

    @Test
    void nullMetadataValuesAreAccepted() {
        // Regression: Map.copyOf rejects null values, which turned every
        // logger.error("...", {"error": exception.getMessage()}) call into an NPE thrown from
        // inside the caller's catch block. A logging call must never take the caller down.
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("clientId", "client-a");
        metadata.put("error", null);

        LogEntry entry = new LogEntry(LogLevel.ERROR, "Send failed.", metadata);

        assertEquals(2, entry.metadata().size());
        assertTrue(entry.metadata().containsKey("error"));
        assertNull(entry.metadata().get("error"));
    }

    @Test
    void nullMetadataValuesSurviveThroughEveryTextSink() {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("error", null);
        LogEntry entry = new LogEntry(LogLevel.ERROR, "Send failed.", metadata);

        assertDoesNotThrow(() -> new ConsoleLogger(
                "test",
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream())).log(entry));
    }

    private static void assertThrows(Class<? extends Throwable> expected, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expected + " but got " + actual.getClass(), actual);
        }
        throw new AssertionError("Expected " + expected + " but no exception was thrown");
    }
}

package dev.orwell.logging;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

package dev.orwell.logging;

import java.util.Map;

public record LogEntry(
        LogLevel level,
        String message,
        Map<String, Object> metadata
) {
    public LogEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

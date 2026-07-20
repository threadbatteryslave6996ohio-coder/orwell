package dev.orwell.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One log record: a level, a message, and arbitrary structured metadata.
 *
 * <p>Metadata values are allowed to be null. Callers routinely pass things like
 * {@code exception.getMessage()}, which is null for any exception built without a message, and a
 * logging call must never be the thing that brings a caller down — so the defensive copy here uses
 * a {@link LinkedHashMap} rather than {@code Map.copyOf}, which rejects nulls.
 */
public record LogEntry(
        LogLevel level,
        String message,
        Map<String, Object> metadata
) {
    public LogEntry {
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}

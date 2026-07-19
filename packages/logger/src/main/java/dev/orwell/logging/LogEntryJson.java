package dev.orwell.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shapes a {@link LogEntry} into the flat JSON payload both machine-readable sinks emit, so the
 * on-disk and over-the-wire representations cannot drift apart.
 *
 * <p>Layout is {@code timestamp}, {@code level}, {@code message}, then the entry's metadata
 * flattened alongside them. Metadata keys colliding with those three reserved names are dropped
 * rather than allowed to overwrite them.
 */
final class LogEntryJson {
    static final String TIMESTAMP = "timestamp";
    static final String LEVEL = "level";
    static final String MESSAGE = "message";

    private LogEntryJson() {
    }

    /**
     * @param at when the entry was recorded. Passed in rather than read here because a batching
     *           sink stamps entries on arrival and flushes them later; reading the clock at flush
     *           time would smear a batch onto a single instant.
     */
    static Map<String, Object> payload(LogEntry entry, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TIMESTAMP, at.toString());
        payload.put(LEVEL, entry.level().name());
        payload.put(MESSAGE, entry.message());
        entry.metadata().forEach((key, value) -> {
            if (!TIMESTAMP.equals(key) && !LEVEL.equals(key) && !MESSAGE.equals(key)) {
                payload.put(key, value);
            }
        });
        return payload;
    }
}

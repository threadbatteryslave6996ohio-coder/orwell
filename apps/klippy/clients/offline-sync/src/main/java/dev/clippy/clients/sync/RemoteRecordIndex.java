package dev.clippy.clients.sync;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * In-memory index of clipboard entries already present on the server, used to avoid re-sending them.
 * Timestamps are matched within a small tolerance because the server may round sub-microsecond values.
 */
final class RemoteRecordIndex {
    private static final long TIMESTAMP_TOLERANCE_NANOS = 999;
    private final Map<RecordContentKey, NavigableSet<Instant>> timestamps = new HashMap<>();

    void add(String clientId, String content, Instant timestamp) {
        timestamps.computeIfAbsent(new RecordContentKey(clientId, content), ignored -> new TreeSet<>())
                .add(timestamp);
    }

    void add(ClipboardRecord record) {
        add(record.clientId(), record.content(), record.timestamp());
    }

    boolean contains(ClipboardRecord candidate) {
        NavigableSet<Instant> matchingTimestamps = timestamps.get(
                new RecordContentKey(candidate.clientId(), candidate.content()));
        if (matchingTimestamps == null) {
            return false;
        }
        Instant closest = matchingTimestamps.ceiling(
                candidate.timestamp().minusNanos(TIMESTAMP_TOLERANCE_NANOS));
        return closest != null
                && !closest.isAfter(candidate.timestamp().plusNanos(TIMESTAMP_TOLERANCE_NANOS));
    }

    private record RecordContentKey(String clientId, String content) {
    }
}

package dev.clippy.clients.core;

import java.time.Instant;
import java.util.Objects;

public record ClipboardEntry(String clientId, String content, Instant timestamp) {
    public ClipboardEntry {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}

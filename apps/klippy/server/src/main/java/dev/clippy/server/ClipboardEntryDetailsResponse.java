package dev.clippy.server;

import java.time.Instant;

public record ClipboardEntryDetailsResponse(
        Long id,
        String clientId,
        String content,
        Instant timestamp
) {
    static ClipboardEntryDetailsResponse from(ClipboardEntry entry) {
        return new ClipboardEntryDetailsResponse(
                entry.getId(),
                entry.getClientId(),
                entry.getContent(),
                entry.getTimestamp()
        );
    }
}

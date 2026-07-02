package dev.clippy.server;

import java.time.Instant;

public record ClipboardEntryResponse(
        Long id,
        String clientId,
        Instant timestamp
) {
}

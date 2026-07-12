package dev.orwell.server.dto;

import java.time.Instant;

public record ClipboardEntryResponse(
        Long id,
        String clientId,
        Instant timestamp
) {
}

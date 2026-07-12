package dev.orwell.server.dto;

import dev.orwell.server.model.ClipboardEntry;

import java.time.Instant;

public record ClipboardEntryDetailsResponse(
        Long id,
        String clientId,
        String content,
        Instant timestamp
) {
    public static ClipboardEntryDetailsResponse from(ClipboardEntry entry) {
        return new ClipboardEntryDetailsResponse(
                entry.getId(),
                entry.getClientId(),
                entry.getContent(),
                entry.getTimestamp()
        );
    }
}

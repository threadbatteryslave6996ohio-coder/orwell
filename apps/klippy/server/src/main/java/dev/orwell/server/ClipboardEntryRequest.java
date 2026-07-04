package dev.orwell.server;

import dev.orwell.utils.ClipboardLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ClipboardEntryRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotNull
        @Size(max = ClipboardLimits.MAX_CONTENT_CHARACTERS)
        String content,

        Instant timestamp
) {
}

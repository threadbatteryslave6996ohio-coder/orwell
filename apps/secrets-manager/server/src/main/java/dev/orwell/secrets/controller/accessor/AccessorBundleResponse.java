package dev.orwell.secrets.controller.accessor;

import java.time.Instant;

public record AccessorBundleResponse(
        Long id,
        String name,
        String description,
        Instant createdAt
) {
}

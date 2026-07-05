package dev.orwell.secrets.controller.accessor;

import java.time.Instant;

public record AccessorEnvironmentResponse(
        Long id,
        String name,
        String value,
        Instant createdAt,
        Instant updatedAt
) {
}

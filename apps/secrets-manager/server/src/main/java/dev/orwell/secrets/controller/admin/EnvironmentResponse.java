package dev.orwell.secrets.controller.admin;

import java.time.Instant;

public record EnvironmentResponse(
        Long id,
        String name,
        String value,
        Instant createdAt,
        Instant updatedAt
) {
}

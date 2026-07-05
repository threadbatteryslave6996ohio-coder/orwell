package dev.orwell.secrets.controller.admin;

import java.time.Instant;

public record AdminResponse(
        Long id,
        String name,
        Instant createdAt
) {
}

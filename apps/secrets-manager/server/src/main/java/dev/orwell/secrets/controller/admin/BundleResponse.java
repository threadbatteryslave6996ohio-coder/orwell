package dev.orwell.secrets.controller.admin;

import java.time.Instant;

public record BundleResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        String createdBy
) {
}

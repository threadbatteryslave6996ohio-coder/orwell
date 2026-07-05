package dev.orwell.secrets.controller.accessor;

import java.time.Instant;

public record AccessorGroupResponse(
        Long id,
        String name,
        String description,
        Instant createdAt
) {
}

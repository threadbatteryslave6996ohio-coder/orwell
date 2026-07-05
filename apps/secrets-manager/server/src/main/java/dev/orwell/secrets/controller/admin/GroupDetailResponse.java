package dev.orwell.secrets.controller.admin;

import java.time.Instant;
import java.util.List;

public record GroupDetailResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        String createdBy,
        List<EnvironmentResponse> environments
) {
}

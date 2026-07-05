package dev.orwell.secrets.controller.accessor;

import java.time.Instant;
import java.util.List;

public record AccessorBundleDetailResponse(
        Long id,
        String name,
        String description,
        Instant createdAt,
        List<AccessorEnvironmentResponse> environments
) {
}

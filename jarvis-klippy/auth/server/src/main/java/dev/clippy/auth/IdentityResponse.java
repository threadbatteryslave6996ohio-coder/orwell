package dev.clippy.auth;

import java.time.Instant;

public record IdentityResponse(
        String clientId,
        Instant createdAt
) {
}

package dev.orwell.auth.http.server.dto;

import java.time.Instant;

public record IdentityResponse(
        String clientId,
        Instant createdAt
) {
}

package dev.orwell.auth.http.server;

import java.time.Instant;

public record IdentityResponse(
        String clientId,
        Instant createdAt
) {
}

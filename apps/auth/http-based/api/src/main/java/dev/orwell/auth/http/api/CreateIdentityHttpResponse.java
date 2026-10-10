package dev.orwell.auth.http.api;

import java.time.Instant;

public record CreateIdentityHttpResponse(
        String workspace,
        String clientId,
        Instant createdAt
) {
}

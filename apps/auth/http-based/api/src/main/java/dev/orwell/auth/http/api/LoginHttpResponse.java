package dev.orwell.auth.http.api;

public record LoginHttpResponse(
        String clientId,
        String token
) {
}

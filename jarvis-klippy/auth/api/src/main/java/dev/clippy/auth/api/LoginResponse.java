package dev.clippy.auth.api;

public record LoginResponse(
        String clientId,
        String token
) {
}

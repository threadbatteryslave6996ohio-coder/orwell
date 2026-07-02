package dev.clippy.auth.api;

public record CheckTokenResponse(
        boolean valid,
        String clientId
) {
}

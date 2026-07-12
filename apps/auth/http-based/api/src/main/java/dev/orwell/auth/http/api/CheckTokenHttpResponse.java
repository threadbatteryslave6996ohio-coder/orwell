package dev.orwell.auth.http.api;

public record CheckTokenHttpResponse(
        boolean valid,
        String clientId,
        Long identityId
) {
}

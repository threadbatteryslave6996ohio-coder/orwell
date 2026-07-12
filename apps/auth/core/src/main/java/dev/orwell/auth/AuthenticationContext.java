package dev.orwell.auth;

public record AuthenticationContext(
        boolean authenticated,
        String clientId,
        Long identityId
) {
    public static AuthenticationContext authenticated(String clientId, Long identityId) {
        return new AuthenticationContext(true, clientId, identityId);
    }

    public static AuthenticationContext unauthenticated() {
        return new AuthenticationContext(false, null, null);
    }
}

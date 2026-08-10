package dev.orwell.auth;

public record AuthenticationContext(
        boolean authenticated,
        String clientId
) {
    public static AuthenticationContext authenticated(String clientId) {
        return new AuthenticationContext(true, clientId);
    }

    public static AuthenticationContext unauthenticated() {
        return new AuthenticationContext(false, null);
    }
}

package dev.orwell.auth;

/** Transport-neutral authentication boundary used by application cores. */
@FunctionalInterface
public interface AuthenticationStrategy {
    boolean isTokenValidForClient(String clientId, String token);

    default AuthenticationContext authenticate(String clientId, String token) {
        return isTokenValidForClient(clientId, token)
                ? AuthenticationContext.authenticated(clientId, null)
                : AuthenticationContext.unauthenticated();
    }
}

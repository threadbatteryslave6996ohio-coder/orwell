package dev.orwell.auth;

/** Transport-neutral authentication boundary used by application cores. */
@FunctionalInterface
public interface AuthenticationStrategy {
    boolean isTokenValidForClient(String clientId, String token);
}

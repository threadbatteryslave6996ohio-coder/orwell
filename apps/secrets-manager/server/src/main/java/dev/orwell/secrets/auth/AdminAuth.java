package dev.orwell.secrets.auth;

import dev.orwell.auth.AuthenticationStrategy;

/**
 * The auth deployment that holds admin identities.
 *
 * <p>Deliberately a wrapper rather than an {@code AuthenticationStrategy} implementation: two beans
 * of that type would make {@code server-bootstrap}'s by-type injection of the request-scoped
 * {@code AuthenticationContext} ambiguous. Distinct wrapper types keep by-type resolution unique,
 * so neither bean needs a qualifier.
 */
public record AdminAuth(AuthenticationStrategy strategy) {
    public boolean accepts(String clientId, String token) {
        return strategy.isTokenValidForClient(clientId, token);
    }
}

package dev.orwell.secrets.auth;

import dev.orwell.auth.AuthenticationStrategy;

/**
 * The auth deployment that holds ordinary client identities — the accessors that read secrets.
 *
 * <p>See {@link AdminAuth} for why this is a wrapper type rather than an
 * {@code AuthenticationStrategy} bean.
 */
public record ClientAuth(AuthenticationStrategy strategy) {
    public boolean accepts(String clientId, String token) {
        return strategy.isTokenValidForClient(clientId, token);
    }
}

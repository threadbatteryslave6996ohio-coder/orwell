package dev.orwell.auth.memory;

import dev.orwell.auth.AuthenticationStrategy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable in-memory strategy intended for tests and explicitly local deployments. */
public final class InMemoryAuthenticationStrategy implements AuthenticationStrategy {
    private final Map<String, String> tokensByClientId = new ConcurrentHashMap<>();

    public InMemoryAuthenticationStrategy() {
    }

    public InMemoryAuthenticationStrategy(Map<String, String> initialTokens) {
        tokensByClientId.putAll(initialTokens);
    }

    public void register(String clientId, String token) {
        tokensByClientId.put(requireValue(clientId, "clientId"), requireValue(token, "token"));
    }

    public void revoke(String clientId) {
        if (clientId != null) {
            tokensByClientId.remove(clientId);
        }
    }

    @Override
    public boolean isTokenValidForClient(String clientId, String token) {
        return clientId != null && token != null && token.equals(tokensByClientId.get(clientId));
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

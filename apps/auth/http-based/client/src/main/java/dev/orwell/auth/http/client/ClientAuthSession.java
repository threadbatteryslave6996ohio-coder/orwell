package dev.orwell.auth.http.client;

import dev.orwell.auth.http.api.LoginHttpResponse;

import java.util.Objects;

/**
 * Caches a bearer token from the auth server for a single client identity and refreshes it on
 * demand. Two modes: when a client secret is configured the session can {@link #refresh()} by
 * logging in again (e.g. after a 401); otherwise it serves a fixed initial token and cannot
 * refresh. All state changes are synchronized, so one session is safe to share across request
 * threads.
 */
public final class ClientAuthSession {
    private final String authServerUrl;
    private final String clientId;
    private final String clientSecret;
    private String clientToken;

    public ClientAuthSession(String authServerUrl, String clientId, String clientSecret, String initialToken) {
        this.authServerUrl = normalizedOrNull(authServerUrl);
        this.clientId = normalized(clientId, "clientId");
        this.clientSecret = normalizedOrNull(clientSecret);
        this.clientToken = normalizedOrNull(initialToken);
    }

    public String clientId() {
        return clientId;
    }

    public synchronized String token() {
        if (clientToken == null) {
            if (!canRefresh()) {
                throw new IllegalStateException("An initial token is required when no client secret is configured for refresh.");
            }
            refresh();
        }
        return clientToken;
    }

    public synchronized boolean canRefresh() {
        return clientSecret != null;
    }

    public synchronized boolean hasToken() {
        return clientToken != null;
    }

    public synchronized String refresh() {
        if (!canRefresh()) {
            throw new IllegalStateException("A client secret is required to request a fresh token from the auth server.");
        }
        if (authServerUrl == null) {
            throw new IllegalStateException("An auth server URL is required to request a fresh token from the auth server.");
        }

        HttpAuthenticationStrategy authClient = new HttpAuthenticationStrategy(authServerUrl);
        LoginHttpResponse response = authClient.login(clientId, clientSecret);
        clientToken = normalized(response.token(), "token");
        return clientToken;
    }

    public synchronized boolean refreshIfUnauthorized(int statusCode) {
        if (statusCode != 401 || !canRefresh()) {
            return false;
        }

        refresh();
        return true;
    }

    private static String normalized(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank.");
        }
        return normalized;
    }

    private static String normalizedOrNull(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

package dev.clippy.clients.core.env;

import dev.clippy.auth.api.LoginResponse;
import dev.clippy.auth.client.ClippyAuthClient;

import java.util.Objects;

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

    public synchronized String token() {
        if (clientToken == null) {
            if (!canRefresh()) {
                throw new IllegalStateException("CLIENT_TOKEN is required when CLIENT_SECRET is not set.");
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
            throw new IllegalStateException("CLIENT_SECRET is required to request a fresh token from the auth server.");
        }
        if (authServerUrl == null) {
            throw new IllegalStateException("AUTH_SERVER_URL is required to request a fresh token from the auth server.");
        }

        ClippyAuthClient authClient = new ClippyAuthClient(authServerUrl);
        LoginResponse response = authClient.login(clientId, clientSecret);
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

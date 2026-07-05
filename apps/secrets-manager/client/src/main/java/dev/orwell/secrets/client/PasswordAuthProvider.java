package dev.orwell.secrets.client;

import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;

import java.util.Objects;

public class PasswordAuthProvider implements SecretsAuthProvider {
    private final HttpAuthenticationStrategy authStrategy;
    private final String clientId;
    private final String secret;
    private volatile String cachedToken;

    public PasswordAuthProvider(String authServerUrl, String clientId, String secret) {
        Objects.requireNonNull(authServerUrl, "authServerUrl");
        this.authStrategy = new HttpAuthenticationStrategy(authServerUrl);
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.secret = Objects.requireNonNull(secret, "secret");
    }

    @Override
    public String getAuthorizationHeader() {
        if (cachedToken == null) {
            login();
        }
        return "Bearer " + cachedToken;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    private synchronized void login() {
        if (cachedToken != null) {
            return;
        }
        try {
            var response = authStrategy.login(clientId, secret);
            cachedToken = response.token();
        } catch (HttpAuthenticationException e) {
            throw new SecretsManagerException("Login failed: " + e.getMessage(), e);
        }
    }
}

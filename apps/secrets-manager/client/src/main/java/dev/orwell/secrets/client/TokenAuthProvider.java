package dev.orwell.secrets.client;

import java.util.Objects;

public class TokenAuthProvider implements SecretsAuthProvider {
    private final String token;
    private final String clientId;

    public TokenAuthProvider(String token, String clientId) {
        this.token = Objects.requireNonNull(token, "token");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
    }

    @Override
    public String getAuthorizationHeader() {
        return "Bearer " + token;
    }

    @Override
    public String getClientId() {
        return clientId;
    }
}

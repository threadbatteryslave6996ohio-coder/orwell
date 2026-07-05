package dev.orwell.secrets.client;

public interface SecretsAuthProvider {
    String getAuthorizationHeader();
    String getClientId();
}

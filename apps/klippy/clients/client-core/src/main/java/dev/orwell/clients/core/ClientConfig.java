package dev.orwell.clients.core;

import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;

import java.net.URI;
import java.util.Objects;

public record ClientConfig(
        URI endpoint,
        String authServerUrl,
        String clientId,
        String clientSecret,
        String clientToken,
        ClientAuthSession authSession
) {
    public ClientConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(authSession, "authSession");
    }

    public static ClientConfig load(Env env, String defaultClientId) {
        String authServerUrl = optional(env, ClientEnvs.AUTH_SERVER_URL);
        String clientId = env.has(ClientEnvs.CLIENT_ID) ? env.get(ClientEnvs.CLIENT_ID) : defaultClientId;
        String clientSecret = optional(env, ClientEnvs.CLIENT_SECRET);
        String clientToken = optional(env, ClientEnvs.CLIENT_TOKEN);
        ClientAuthSession authSession = new ClientAuthSession(authServerUrl, clientId, clientSecret, clientToken);
        return new ClientConfig(
                ClipboardEndpoint.from(env.get(ClientEnvs.REMOTE_SERVER_URL)),
                authServerUrl,
                clientId,
                clientSecret,
                clientToken,
                authSession);
    }

    private static <T> T optional(Env env, dev.orwell.env.EnvOption<T> option) {
        return env.has(option) ? env.get(option) : null;
    }
}

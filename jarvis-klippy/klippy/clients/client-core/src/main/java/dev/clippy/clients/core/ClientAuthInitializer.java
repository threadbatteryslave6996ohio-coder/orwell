package dev.clippy.clients.core;

import dev.clippy.clients.core.env.ClientAuthSession;

public final class ClientAuthInitializer {
    private ClientAuthInitializer() {
    }

    public static void initialize(ClientAuthSession authSession, ClientConfig config) {
        initialize(authSession, config.authServerUrl(), config.clientId());
    }

    public static void initialize(ClientAuthSession authSession, String authServerUrl, String clientId) {
        if (authSession.canRefresh()) {
            if (authServerUrl == null) {
                throw new IllegalStateException("AUTH_SERVER_URL is required when CLIENT_SECRET is set.");
            }
            System.out.printf("Refreshing auth token from %s for clientId=%s%n", authServerUrl, clientId);
            authSession.refresh();
        } else if (!authSession.hasToken()) {
            throw new IllegalStateException("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.");
        }
    }
}

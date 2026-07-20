package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientAuthSession;
import dev.orwell.logging.Logger;

import java.util.Map;

public final class ClientAuthInitializer {
    private ClientAuthInitializer() {
    }

    public static void initialize(ClientAuthSession authSession, ClientConfig config, Logger logger) {
        initialize(authSession, config.authServerUrl(), config.clientId(), logger);
    }

    public static void initialize(
            ClientAuthSession authSession, String authServerUrl, String clientId, Logger logger) {
        if (authSession.canRefresh()) {
            if (authServerUrl == null) {
                throw new IllegalStateException("AUTH_SERVER_URL is required when CLIENT_SECRET is set.");
            }
            logger.info("Refreshing auth token.", Map.of("authServer", authServerUrl, "clientId", clientId));
            authSession.refresh();
        } else if (!authSession.hasToken()) {
            throw new IllegalStateException("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.");
        }
    }
}

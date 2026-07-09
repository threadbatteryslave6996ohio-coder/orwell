package dev.orwell.keeboarder.server;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.BearerToken;

final class KeeboarderRequestAuth {
    private KeeboarderRequestAuth() {
    }

    static boolean isAuthenticated(AuthenticationStrategy authenticator, String clientId, String authorization) {
        if (authenticator == null) {
            return true;
        }
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        String token = BearerToken.extract(authorization);
        return token != null && authenticator.isTokenValidForClient(clientId, token);
    }
}

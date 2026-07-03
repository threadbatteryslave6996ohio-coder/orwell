package com.keeboarder.server;

import dev.clippy.auth.client.ClippyAuthClient;

public final class AuthServerClientAuthenticator implements ClientAuthenticator {
    private final ClippyAuthClient authClient;

    public AuthServerClientAuthenticator(ClippyAuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public boolean isTokenValidForClient(String clientId, String token) {
        return authClient.isTokenValidForClient(clientId, token);
    }
}

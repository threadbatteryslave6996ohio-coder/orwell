package com.keeboarder.server;

public interface ClientAuthenticator {
    boolean isTokenValidForClient(String clientId, String token);
}

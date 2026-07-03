package dev.clippy.server;

public interface AuthTokenVerifier {
    boolean isTokenValidForClient(String clientId, String token);
}

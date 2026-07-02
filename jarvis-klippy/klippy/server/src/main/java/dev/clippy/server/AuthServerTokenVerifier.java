package dev.clippy.server;

import dev.clippy.auth.client.AuthClientException;
import dev.clippy.auth.client.ClippyAuthClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Component
public class AuthServerTokenVerifier implements AuthTokenVerifier {
    private final ClippyAuthClient authClient;

    public AuthServerTokenVerifier(ClippyAuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public boolean isTokenValidForClient(String clientId, String token) {
        try {
            return authClient.isTokenValidForClient(clientId, token);
        } catch (AuthClientException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Cannot reach auth server.", exception);
        }
    }
}

package dev.orwell.secrets.service;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.auth.BearerToken;
import dev.orwell.secrets.auth.AdminAuth;
import dev.orwell.secrets.auth.ClientAuth;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the caller's role from <em>which</em> auth deployment accepts their token: the admin
 * server grants admin, the client server grants accessor. This service stores no identities of its
 * own.
 */
@Component
public class AuthValidator {
    private final AdminAuth adminAuth;
    private final ClientAuth clientAuth;

    public AuthValidator(AdminAuth adminAuth, ClientAuth clientAuth) {
        this.adminAuth = adminAuth;
        this.clientAuth = clientAuth;
    }

    public AuthenticationContext requireAdmin(String authorization, String clientId) {
        String token = requireCredentials(authorization, clientId);
        if (adminAuth.accepts(clientId, token)) {
            return AuthenticationContext.authenticated(clientId, null);
        }
        // A token the *client* server accepts is a real identity that simply is not an admin, and
        // that stays a 403 the way it did when roles were rows in a table. Only a token neither
        // deployment knows is a 401.
        if (clientAuth.accepts(clientId, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required.");
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
    }

    public AuthenticationContext requireAccessor(String authorization, String clientId) {
        String token = requireCredentials(authorization, clientId);
        if (clientAuth.accepts(clientId, token)) {
            return AuthenticationContext.authenticated(clientId, null);
        }
        if (adminAuth.accepts(clientId, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accessor access required.");
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
    }

    private static String requireCredentials(String authorization, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing client id.");
        }
        String token = BearerToken.extract(authorization);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }
        return token;
    }
}

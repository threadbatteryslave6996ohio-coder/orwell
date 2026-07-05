package dev.orwell.secrets.service;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.secrets.repository.AccessorIdentityRepository;
import dev.orwell.secrets.repository.AdminIdentityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthValidator {
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationStrategy authenticationStrategy;
    private final AdminIdentityRepository adminRepo;
    private final AccessorIdentityRepository accessorRepo;

    public AuthValidator(
            AuthenticationStrategy authenticationStrategy,
            AdminIdentityRepository adminRepo,
            AccessorIdentityRepository accessorRepo
    ) {
        this.authenticationStrategy = authenticationStrategy;
        this.adminRepo = adminRepo;
        this.accessorRepo = accessorRepo;
    }

    public void requireAdmin(String authorization, String clientId) {
        validateClientId(clientId);
        validateToken(authorization, clientId);
        if (!adminRepo.existsByName(clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required.");
        }
    }

    public void requireAccessor(String authorization, String clientId) {
        validateClientId(clientId);
        validateToken(authorization, clientId);
        if (!accessorRepo.existsByName(clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accessor access required.");
        }
    }

    private static void validateClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing client id.");
        }
    }

    private void validateToken(String authorization, String clientId) {
        String token = extractBearerToken(authorization);
        if (!authenticationStrategy.isTokenValidForClient(clientId, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
        }
    }

    static String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expected bearer token.");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }
        return token;
    }
}

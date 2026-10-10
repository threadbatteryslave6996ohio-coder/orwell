package dev.orwell.auth.http.server.security;

import dev.orwell.auth.BearerToken;
import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Resolves the caller of a provisioning request to an admin, or refuses it.
 *
 * <p>The credential is the ordinary one every other service in the repo already sends — {@code
 * X-Client-Id} plus an {@code Authorization: Bearer} token — and it is checked here against the
 * database directly rather than over HTTP against this server's own {@code /admin/tokens/check}.
 * A server that authenticated by calling itself would deadlock its own thread pool under load and
 * would report an outage of itself as a rejected credential.
 *
 * <p>What makes the caller an admin is only that the token belongs to an active identity in the
 * {@link AuthWorkspaces#ADMIN} workspace. There is no role column: moving an identity between
 * workspaces is not a thing this server offers, so the grant cannot be edited in place.
 */
@Component
public class AdminTokenGuard {
    private final ClientTokenRepository tokens;
    private final CredentialHasher credentialHasher;
    private final TokenUsage tokenUsage;

    public AdminTokenGuard(ClientTokenRepository tokens, CredentialHasher credentialHasher, TokenUsage tokenUsage) {
        this.tokens = tokens;
        this.credentialHasher = credentialHasher;
        this.tokenUsage = tokenUsage;
    }

    /**
     * The admin's {@code clientId}, or a 401 that says nothing about which half was wrong.
     *
     * <p>Deliberately one message for a missing header, a token that is not an admin's, and a
     * token that is nobody's: the endpoint it guards is the one that mints credentials, so a
     * caller probing it learns only that it was refused.
     */
    public String requireAdmin(String clientIdHeader, String authorizationHeader) {
        String token = BearerToken.extract(authorizationHeader);
        if (clientIdHeader == null || clientIdHeader.isBlank() || token == null) {
            throw unauthorized();
        }

        ClientToken accepted = tokens.findWithIdentityByTokenHash(credentialHasher.hashToken(token))
                .filter(candidate -> candidate.getIdentity().isActive())
                .filter(candidate -> AuthWorkspaces.ADMIN.equals(candidate.getIdentity().getWorkspace()))
                .filter(candidate -> clientIdHeader.equals(candidate.getIdentity().getClientId()))
                .orElseThrow(AdminTokenGuard::unauthorized);

        // Provisioning is a use of the admin's token like any other, and an admin that provisions
        // daily but never calls /tokens/check would otherwise look idle to the cleanup job.
        tokenUsage.record(accepted, Instant.now());

        return accepted.getIdentity().getClientId();
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "An admin token is required to provision identities.");
    }
}

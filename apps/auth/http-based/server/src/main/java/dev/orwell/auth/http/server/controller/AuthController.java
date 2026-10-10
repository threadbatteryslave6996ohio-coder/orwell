package dev.orwell.auth.http.server.controller;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.api.CheckTokenHttpRequest;
import dev.orwell.auth.http.api.CheckTokenHttpResponse;
import dev.orwell.auth.http.api.CreateIdentityHttpRequest;
import dev.orwell.auth.http.api.CreateIdentityHttpResponse;
import dev.orwell.auth.http.api.LoginHttpRequest;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientIdentityRepository;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import dev.orwell.auth.http.server.security.AdminTokenGuard;
import dev.orwell.auth.http.server.security.CredentialHasher;
import dev.orwell.auth.http.server.security.TokenGenerator;
import dev.orwell.auth.http.server.security.TokenUsage;
import dev.orwell.logging.Logger;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Every route is scoped to a workspace, which is the first path segment after the optional route
 * prefix: {@code /def/login}, {@code /admin/login}, {@code /team-a/tokens/check}. A workspace is
 * not registered anywhere — the first identity created in a name brings it into existence, and the
 * last one deleted takes it away.
 *
 * @see AuthWorkspaces
 */
@RestController
@RequestMapping("${orwell.auth.route-prefix:}/{workspace}")
public class AuthController {
    private final ClientIdentityRepository identities;
    private final ClientTokenRepository tokens;
    private final CredentialHasher credentialHasher;
    private final TokenGenerator tokenGenerator;
    private final AdminTokenGuard adminTokenGuard;
    private final TokenUsage tokenUsage;
    private final Logger logger;

    public AuthController(
            ClientIdentityRepository identities,
            ClientTokenRepository tokens,
            CredentialHasher credentialHasher,
            TokenGenerator tokenGenerator,
            AdminTokenGuard adminTokenGuard,
            TokenUsage tokenUsage,
            Logger logger
    ) {
        this.identities = identities;
        this.tokens = tokens;
        this.credentialHasher = credentialHasher;
        this.tokenGenerator = tokenGenerator;
        this.adminTokenGuard = adminTokenGuard;
        this.tokenUsage = tokenUsage;
        this.logger = logger;
    }

    /**
     * Creates an identity in {@code workspace}, for a caller holding an admin token.
     *
     * <p>The admin lives in the {@code admin} workspace, whichever workspace is being written to —
     * including {@code admin} itself, which is what stops this endpoint from being the way a new
     * admin appears. The first one comes from {@code AUTH_ADMIN_BOOTSTRAP_*} at startup instead;
     * see {@code AdminBootstrap}.
     */
    @PostMapping("/identities")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateIdentityHttpResponse createIdentity(
            @PathVariable String workspace,
            @RequestHeader(value = "X-Client-Id", required = false) String adminClientId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody CreateIdentityHttpRequest request
    ) {
        String target = requireValidWorkspace(workspace);
        String admin = adminTokenGuard.requireAdmin(adminClientId, authorization);

        if (identities.existsByWorkspaceAndClientId(target, request.clientId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client identity already exists.");
        }

        ClientIdentity identity = new ClientIdentity(
                target,
                request.clientId(),
                credentialHasher.hashSecret(request.secret()),
                Instant.now()
        );

        try {
            ClientIdentity saved = identities.save(identity);
            logger.info("Created identity.", Map.of(
                    "workspace", target,
                    "clientId", saved.getClientId(),
                    "createdBy", admin));
            return new CreateIdentityHttpResponse(saved.getWorkspace(), saved.getClientId(), saved.getCreatedAt());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client identity already exists.", exception);
        }
    }

    @PostMapping("/login")
    public LoginHttpResponse login(@PathVariable String workspace, @Valid @RequestBody LoginHttpRequest request) {
        String target = requireValidWorkspace(workspace);
        logger.info("Login request received.", Map.of("workspace", target, "clientId", request.clientId()));

        ClientIdentity identity = identities.findByWorkspaceAndClientId(target, request.clientId())
                .filter(ClientIdentity::isActive)
                .filter(candidate -> credentialHasher.matches(request.secret(), candidate.getSecretHash()))
                .orElseThrow(() -> {
                    logger.warn("Login rejected.", Map.of("workspace", target, "clientId", request.clientId()));
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials.");
                });

        String token = tokenGenerator.newToken();
        Instant issuedAt = Instant.now();
        tokens.save(new ClientToken(identity, credentialHasher.hashToken(token), issuedAt));
        logger.info("Issued login token.", Map.of(
                "workspace", target,
                "clientId", identity.getClientId(),
                "issuedAt", issuedAt));

        return new LoginHttpResponse(identity.getClientId(), token);
    }

    @PostMapping("/tokens/check")
    public CheckTokenHttpResponse checkToken(@PathVariable String workspace, @Valid @RequestBody CheckTokenHttpRequest request) {
        String target = requireValidWorkspace(workspace);

        Optional<ClientToken> accepted = tokens.findWithIdentityByTokenHash(credentialHasher.hashToken(request.token()))
                .filter(token -> token.getIdentity().isActive())
                // The workspace is checked, not assumed from the clientId: a token issued in one
                // workspace must not authenticate a same-named identity in another, or the
                // namespacing would be cosmetic.
                .filter(token -> target.equals(token.getIdentity().getWorkspace()))
                .filter(token -> request.clientId().equals(token.getIdentity().getClientId()));
        boolean valid = accepted.isPresent();

        // Only a token that actually authenticated the caller counts as used — a rejected one must
        // keep ageing toward collection. Throttled inside TokenUsage, since this is the busiest
        // path in the stack and it must not become a row write per request.
        accepted.ifPresent(token -> tokenUsage.record(token, Instant.now()));

        // One record per check, not a "received" line as well: every authenticated request to every
        // service in the stack lands here, so this is the highest-volume log site in the repo and a
        // second line per call would say nothing the outcome does not.
        logger.info("Token check completed.", Map.of(
                "workspace", target,
                "clientId", request.clientId(),
                "valid", valid));

        // The clientId echoed back is the request's own: the filter above only passes an identity
        // whose clientId equals it, so reading it off the identity said the same thing indirectly.
        return new CheckTokenHttpResponse(valid, request.clientId());
    }

    /**
     * A workspace that could not have been created is a bad request, not an empty one: answering
     * 401/404 for {@code /Def} or {@code /../etc} would let a caller mistake a rejected name for a
     * wrong credential.
     */
    private static String requireValidWorkspace(String workspace) {
        if (!AuthWorkspaces.isValid(workspace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a usable workspace name.");
        }
        return workspace;
    }
}

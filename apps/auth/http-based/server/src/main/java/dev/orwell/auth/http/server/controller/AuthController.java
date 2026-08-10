package dev.orwell.auth.http.server.controller;

import dev.orwell.auth.http.api.CheckTokenHttpRequest;
import dev.orwell.auth.http.api.CheckTokenHttpResponse;
import dev.orwell.auth.http.api.LoginHttpRequest;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.server.dto.CreateIdentityRequest;
import dev.orwell.auth.http.server.dto.IdentityResponse;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientIdentityRepository;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import dev.orwell.auth.http.server.security.CredentialHasher;
import dev.orwell.auth.http.server.security.TokenGenerator;
import dev.orwell.logging.Logger;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("${orwell.auth.route-prefix:}")
public class AuthController {
    private final ClientIdentityRepository identities;
    private final ClientTokenRepository tokens;
    private final CredentialHasher credentialHasher;
    private final TokenGenerator tokenGenerator;
    private final Logger logger;

    public AuthController(
            ClientIdentityRepository identities,
            ClientTokenRepository tokens,
            CredentialHasher credentialHasher,
            TokenGenerator tokenGenerator,
            Logger logger
    ) {
        this.identities = identities;
        this.tokens = tokens;
        this.credentialHasher = credentialHasher;
        this.tokenGenerator = tokenGenerator;
        this.logger = logger;
    }

    @PostMapping("/identities")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityResponse createIdentity(@Valid @RequestBody CreateIdentityRequest request) {
        if (identities.existsByClientId(request.clientId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client identity already exists.");
        }

        ClientIdentity identity = new ClientIdentity(
                request.clientId(),
                credentialHasher.hashSecret(request.secret()),
                Instant.now()
        );

        try {
            ClientIdentity saved = identities.save(identity);
            return new IdentityResponse(saved.getClientId(), saved.getCreatedAt());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client identity already exists.", exception);
        }
    }

    @PostMapping("/login")
    public LoginHttpResponse login(@Valid @RequestBody LoginHttpRequest request) {
        logger.info("Login request received.", Map.of("clientId", request.clientId()));

        ClientIdentity identity = identities.findByClientId(request.clientId())
                .filter(ClientIdentity::isActive)
                .filter(candidate -> credentialHasher.matches(request.secret(), candidate.getSecretHash()))
                .orElseThrow(() -> {
                    logger.warn("Login rejected.", Map.of("clientId", request.clientId()));
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials.");
                });

        String token = tokenGenerator.newToken();
        Instant issuedAt = Instant.now();
        tokens.save(new ClientToken(identity, credentialHasher.hashToken(token), issuedAt));
        logger.info("Issued login token.", Map.of("clientId", identity.getClientId(), "issuedAt", issuedAt));

        return new LoginHttpResponse(identity.getClientId(), token);
    }

    @PostMapping("/tokens/check")
    public CheckTokenHttpResponse checkToken(@Valid @RequestBody CheckTokenHttpRequest request) {
        boolean valid = tokens.findWithIdentityByTokenHash(credentialHasher.hashToken(request.token()))
                .map(ClientToken::getIdentity)
                .filter(ClientIdentity::isActive)
                .filter(identity -> request.clientId().equals(identity.getClientId()))
                .isPresent();

        // One record per check, not a "received" line as well: every authenticated request to every
        // service in the stack lands here, so this is the highest-volume log site in the repo and a
        // second line per call would say nothing the outcome does not.
        logger.info("Token check completed.", Map.of("clientId", request.clientId(), "valid", valid));

        // The clientId echoed back is the request's own: the filter above only passes an identity
        // whose clientId equals it, so reading it off the identity said the same thing indirectly.
        return new CheckTokenHttpResponse(valid, request.clientId());
    }
}

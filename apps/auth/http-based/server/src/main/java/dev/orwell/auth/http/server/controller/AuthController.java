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
import dev.orwell.logging.CustomLogger;
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

@RestController
@RequestMapping("${orwell.auth.route-prefix:}")
public class AuthController {
    private final ClientIdentityRepository identities;
    private final ClientTokenRepository tokens;
    private final CredentialHasher credentialHasher;
    private final TokenGenerator tokenGenerator;
    private final CustomLogger logger;

    public AuthController(
            ClientIdentityRepository identities,
            ClientTokenRepository tokens,
            CredentialHasher credentialHasher,
            TokenGenerator tokenGenerator,
            CustomLogger logger
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
        logger.log("Login request received for clientId=" + request.clientId());

        ClientIdentity identity = identities.findByClientId(request.clientId())
                .filter(ClientIdentity::isActive)
                .filter(candidate -> credentialHasher.matches(request.secret(), candidate.getSecretHash()))
                .orElseThrow(() -> {
                    logger.log("Login rejected for clientId=" + request.clientId());
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials.");
                });

        String token = tokenGenerator.newToken();
        Instant issuedAt = Instant.now();
        tokens.save(new ClientToken(identity, credentialHasher.hashToken(token), issuedAt));
        logger.log("Issued login token for clientId=" + identity.getClientId() + " at " + issuedAt);

        return new LoginHttpResponse(identity.getClientId(), token);
    }

    @PostMapping("/tokens/check")
    public CheckTokenHttpResponse checkToken(@Valid @RequestBody CheckTokenHttpRequest request) {
        logger.log("Token check request received for clientId=" + request.clientId());

        return tokens.findWithIdentityByTokenHash(credentialHasher.hashToken(request.token()))
                .map(ClientToken::getIdentity)
                .filter(ClientIdentity::isActive)
                .filter(identity -> request.clientId().equals(identity.getClientId()))
                .map(identity -> {
                    logger.log("Token check completed for clientId=" + request.clientId() + ", valid=true");
                    return new CheckTokenHttpResponse(true, identity.getClientId(), identity.getId());
                })
                .orElseGet(() -> {
                    logger.log("Token check completed for clientId=" + request.clientId() + ", valid=false");
                    return new CheckTokenHttpResponse(false, request.clientId(), null);
                });
    }
}

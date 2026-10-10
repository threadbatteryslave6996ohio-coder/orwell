package dev.orwell.auth.http.server;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.repository.ClientIdentityRepository;
import dev.orwell.auth.http.server.security.CredentialHasher;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Creates the first identity in the {@code admin} workspace from configuration, because no HTTP
 * route can: {@code POST /admin/identities} needs an admin token, and on an empty database nobody
 * holds one.
 *
 * <p>Runs on every startup and is idempotent — it creates the identity only when that {@code
 * clientId} is absent, so a restart neither duplicates it nor resets a secret that was rotated
 * afterwards. Leave {@code AUTH_ADMIN_BOOTSTRAP_CLIENT_ID} unset once real admins exist; the
 * server then has no configured credential at all, which is the state to keep it in.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    /** Matches {@code CreateIdentityHttpRequest}, so a bootstrapped secret is no weaker than a provisioned one. */
    private static final int MINIMUM_SECRET_LENGTH = 8;

    private final ClientIdentityRepository identities;
    private final CredentialHasher credentialHasher;
    private final Logger logger;
    private final String clientId;
    private final String secret;

    public AdminBootstrap(
            ClientIdentityRepository identities,
            CredentialHasher credentialHasher,
            Logger logger,
            @Value("${orwell.auth.admin-bootstrap.client-id:}") String clientId,
            @Value("${orwell.auth.admin-bootstrap.secret:}") String secret
    ) {
        this.identities = identities;
        this.credentialHasher = credentialHasher;
        this.logger = logger;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.secret = secret == null ? "" : secret;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (clientId.isEmpty()) {
            return;
        }
        // Configured but unusable fails startup rather than logging and carrying on: the operator
        // asked for an admin, and a server that silently has none looks identical to a healthy one
        // until the first provisioning call fails.
        if (secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "AUTH_ADMIN_BOOTSTRAP_SECRET must be at least %d characters.".formatted(MINIMUM_SECRET_LENGTH));
        }

        if (identities.existsByWorkspaceAndClientId(AuthWorkspaces.ADMIN, clientId)) {
            logger.info("Bootstrap admin already present.", Map.of(
                    "workspace", AuthWorkspaces.ADMIN,
                    "clientId", clientId));
            return;
        }

        identities.save(new ClientIdentity(
                AuthWorkspaces.ADMIN,
                clientId,
                credentialHasher.hashSecret(secret),
                Instant.now()));
        logger.warn("Created bootstrap admin identity from configuration.", Map.of(
                "workspace", AuthWorkspaces.ADMIN,
                "clientId", clientId));
    }
}

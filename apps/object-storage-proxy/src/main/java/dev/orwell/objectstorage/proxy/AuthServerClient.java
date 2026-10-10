package dev.orwell.objectstorage.proxy;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.api.CreateIdentityHttpResponse;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The upload clients' auth server: logs them in, and provisions their identities.
 *
 * <p>Provisioning used to travel on a shared secret header, {@code X-Identity-Provisioning-Key},
 * which the auth server never actually checked — the endpoint was open. It now requires a token
 * issued in that server's {@code admin} workspace, so this service holds admin credentials of its
 * own ({@code AUTH_ADMIN_CLIENT_ID} / {@code AUTH_ADMIN_SECRET}) and logs in with them like any
 * other client. The identities it creates land in the ordinary workspace, never {@code admin}:
 * an upload client cannot be minted with the power to mint.
 */
@Component
public class AuthServerClient {
    private final HttpAuthenticationStrategy authClient;
    private final ClientAuthSession adminSession;
    private final FileAuditLogger audit;

    @Autowired
    public AuthServerClient(ProxyProperties properties, FileAuditLogger audit, Logger logger) {
        this(new HttpAuthenticationStrategy(
                        properties.authServer().baseUrl(), properties.authServer().workspace(), logger),
                new ClientAuthSession(
                        properties.authServer().baseUrl(),
                        AuthWorkspaces.ADMIN,
                        blankToPlaceholder(properties.authServer().adminClientId()),
                        adminSecretIfConfigured(
                                properties.authServer().adminClientId(), properties.authServer().adminSecret()),
                        null,
                        logger),
                audit);
    }

    AuthServerClient(HttpAuthenticationStrategy authClient, ClientAuthSession adminSession, FileAuditLogger audit) {
        this.authClient = authClient;
        this.adminSession = adminSession;
        this.audit = audit;
    }

    public AuthCallResult login(String clientId, String secret) {
        try {
            audit.write("authserver.login.send", Map.of("clientId", clientId));
            LoginHttpResponse response = authClient.login(clientId, secret);
            audit.write("authserver.login.response", Map.of("clientId", clientId, "statusCode", 200));
            return new AuthCallResult(response.token() != null && !response.token().isBlank(), 200, response.clientId(), response.token());
        } catch (Exception exception) {
            return AuthCalls.failure(audit, "authserver.login", clientId, null, exception);
        }
    }

    public AuthCallResult createIdentity(String clientId, String secret) {
        if (!adminSession.canRefresh()) {
            // No admin credentials configured at all. 503, not 401: nothing the operator of the
            // panel typed is wrong, and the message the panel shows should send them to the
            // deployment's configuration rather than to their own password.
            audit.write("authserver.identity.create.error",
                    Map.of("clientId", clientId, "error", "No admin credentials configured for identity provisioning."));
            return new AuthCallResult(false, 503, clientId, null);
        }

        // Resolved before the call, and its failure reported separately, because the two 401s mean
        // opposite things. This one is *our* credentials being refused — the deployment's
        // AUTH_ADMIN_CLIENT_ID/SECRET are wrong — and answering the panel 401 would tell whoever is
        // using it that their own password was rejected. A 401 from the create below really is
        // about the request they made.
        String adminToken;
        try {
            adminToken = adminSession.token();
        } catch (Exception exception) {
            return adminCredentialsRejected(clientId, exception);
        }

        try {
            audit.write("authserver.identity.create.send", Map.of("clientId", clientId));
            CreateIdentityHttpResponse response = provision(clientId, secret, adminToken);
            audit.write("authserver.identity.create.response", Map.of("clientId", clientId, "statusCode", 201));
            return new AuthCallResult(true, 201, response == null ? clientId : response.clientId(), null);
        } catch (AdminLoginRejected rejected) {
            return adminCredentialsRejected(clientId, rejected.getCause());
        } catch (Exception exception) {
            return AuthCalls.failure(audit, "authserver.identity.create", clientId, clientId, exception);
        }
    }

    /**
     * Always a 503, never the 401 the auth server actually sent: the panel's operator did not type
     * these credentials and cannot fix them, so the message has to point at the deployment's
     * configuration. Same reasoning as the unconfigured case above, for the case where they are
     * configured and wrong.
     */
    private AuthCallResult adminCredentialsRejected(String clientId, Throwable exception) {
        audit.write("authserver.identity.create.admin_login_rejected", Map.of(
                "clientId", clientId,
                "error", String.valueOf(exception == null ? null : exception.getMessage())));
        return new AuthCallResult(false, 503, clientId, null);
    }

    /** A failure of this service's own admin credentials, told apart from a failure of the call. */
    private static final class AdminLoginRejected extends RuntimeException {
        AdminLoginRejected(Throwable cause) {
            super(cause);
        }
    }

    /**
     * One retry on 401, because the admin token is cached across calls and the auth server can stop
     * accepting it between two of them. Exactly one: a second 401 after a fresh login is a
     * credential the server rejects, and retrying that is a login loop against an endpoint with no
     * rate limit.
     *
     * <p>The refresh is the other place our own credentials can be refused, so a failure there is
     * an {@link AdminLoginRejected} rather than a 401 passed to the caller.
     */
    private CreateIdentityHttpResponse provision(String clientId, String secret, String adminToken) {
        try {
            return authClient.createIdentity(adminSession.clientId(), adminToken, clientId, secret);
        } catch (HttpAuthenticationException exception) {
            if (!isUnauthorized(exception)) {
                throw exception;
            }
            audit.write("authserver.identity.create.admin_token_refresh", Map.of("clientId", clientId));
            String refreshed;
            try {
                refreshed = adminSession.refresh();
            } catch (Exception refreshFailure) {
                throw new AdminLoginRejected(refreshFailure);
            }
            try {
                return authClient.createIdentity(adminSession.clientId(), refreshed, clientId, secret);
            } catch (HttpAuthenticationException retryFailure) {
                // A token minted seconds ago that the identities endpoint still refuses means this
                // identity is not an admin — our configuration again, not the operator's input.
                // Every other status (a 409 for a clientId that exists) is about what they typed
                // and keeps its own answer.
                if (isUnauthorized(retryFailure)) {
                    throw new AdminLoginRejected(retryFailure);
                }
                throw retryFailure;
            }
        }
    }

    private static boolean isUnauthorized(HttpAuthenticationException exception) {
        Integer statusCode = exception.statusCode();
        return statusCode != null && statusCode == 401;
    }

    /**
     * {@link ClientAuthSession} requires a non-blank clientId, but an unconfigured deployment has
     * none — and failing construction would take down uploads, which do not need admin credentials,
     * over a panel feature that does. The placeholder never reaches the network, because
     * {@link #adminSecretIfConfigured} drops the secret alongside it.
     */
    private static String blankToPlaceholder(String adminClientId) {
        return adminClientId == null || adminClientId.isBlank() ? "unconfigured-admin" : adminClientId;
    }

    /**
     * The admin secret, unless there is no admin client id to use it with.
     *
     * <p>This is what makes {@link #createIdentity}'s guard mean what it says. That guard asks
     * {@code adminSession.canRefresh()}, which is answered by the <em>secret</em> — so a
     * deployment that set {@code AUTH_ADMIN_SECRET} and forgot {@code AUTH_ADMIN_CLIENT_ID} used
     * to pass it, log in as the literal placeholder, and show the panel a 401 about the operator's
     * own password rather than a 503 about the deployment's configuration. Dropping the secret
     * with the id makes half-configured indistinguishable from unconfigured, which is what both
     * of them are.
     */
    private static String adminSecretIfConfigured(String adminClientId, String adminSecret) {
        return adminClientId == null || adminClientId.isBlank() ? null : adminSecret;
    }

    public record AuthCallResult(boolean success, int statusCode, String clientId, String token) {}
}

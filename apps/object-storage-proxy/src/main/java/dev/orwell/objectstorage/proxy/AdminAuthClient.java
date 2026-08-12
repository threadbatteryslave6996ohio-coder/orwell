package dev.orwell.objectstorage.proxy;

import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates the management panel against the auth deployment that holds admin identities.
 *
 * <p>The panel used to carry its own auth: one username/password pair read from the environment,
 * and a session cookie this service signed itself with a shared secret. Both are gone. An admin
 * now logs in with an identity the auth server issued, the cookie carries that server's token, and
 * every page load re-checks it against the server — so revoking an admin is one call to the auth
 * server rather than a redeploy of this service with a new secret.
 *
 * <p>It is deliberately a <em>second</em> auth deployment, not the one upload clients use
 * ({@link AuthServerClient}). Which server accepts a credential is what makes it an admin; pointing
 * both at one server would make every upload identity an admin of the panel that mints upload
 * identities. This mirrors secrets-manager's {@code AdminAuth}/{@code ClientAuth} split.
 *
 * <p>Also deliberately not an {@code AuthenticationStrategy} bean: {@code server-bootstrap} injects
 * that type by type to build the request-scoped {@code AuthenticationContext}, and a second bean of
 * it would make that ambiguous. A distinct wrapper type keeps by-type resolution unique, so neither
 * bean needs a qualifier.
 */
@Component
public class AdminAuthClient {
    private final HttpAuthenticationStrategy strategy;
    private final FileAuditLogger audit;

    @Autowired
    public AdminAuthClient(ProxyProperties properties, FileAuditLogger audit) {
        this(new HttpAuthenticationStrategy(properties.adminAuth().baseUrl()), audit);
    }

    AdminAuthClient(HttpAuthenticationStrategy strategy, FileAuditLogger audit) {
        this.strategy = strategy;
        this.audit = audit;
    }

    /** Exchanges admin credentials for an auth-server token. */
    public AuthServerClient.AuthCallResult login(String clientId, String secret) {
        try {
            audit.write("adminauth.login.send", Map.of("clientId", clientId));
            LoginHttpResponse response = strategy.login(clientId, secret);
            audit.write("adminauth.login.response", Map.of("clientId", clientId, "statusCode", 200));
            boolean success = response.token() != null && !response.token().isBlank();
            return new AuthServerClient.AuthCallResult(success, 200, response.clientId(), response.token());
        } catch (HttpAuthenticationException exception) {
            Integer statusCode = exception.statusCode();
            if (statusCode != null) {
                audit.write("adminauth.login.response_error", Map.of("clientId", clientId, "statusCode", statusCode));
                return new AuthServerClient.AuthCallResult(false, statusCode, null, null);
            }
            audit.write("adminauth.login.error", Map.of("clientId", clientId, "error", String.valueOf(exception.getMessage())));
            return new AuthServerClient.AuthCallResult(false, 503, null, null);
        } catch (Exception exception) {
            audit.write("adminauth.login.error", Map.of("clientId", clientId, "error", String.valueOf(exception.getMessage())));
            return new AuthServerClient.AuthCallResult(false, 503, null, null);
        }
    }

    /**
     * Resolves a session cookie to the admin it belongs to, or empty when it is absent, malformed,
     * or no longer accepted.
     *
     * <p>An unreachable admin auth server reads as "not signed in" rather than an error page: the
     * panel's own endpoints already answer 401 with a login form, and the alternative is a 500 on
     * every page load. The same trade-off {@code AuthenticationStrategyConfiguration} makes for the
     * shared request context.
     */
    public Optional<String> signedInAdmin(String sessionCookie) {
        if (sessionCookie == null || sessionCookie.isBlank()) {
            return Optional.empty();
        }
        // Limit 2, so only the first separator splits: the clientId half is base64url (no '.'),
        // and everything after it is the token verbatim, whatever characters the server used.
        String[] parts = sessionCookie.split("\\.", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        String clientId;
        try {
            clientId = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (clientId.isBlank()) {
            return Optional.empty();
        }
        try {
            return strategy.isTokenValidForClient(clientId, parts[1]) ? Optional.of(clientId) : Optional.empty();
        } catch (HttpAuthenticationException exception) {
            audit.write("adminauth.check.error", Map.of("clientId", clientId, "error", String.valueOf(exception.getMessage())));
            return Optional.empty();
        }
    }

    /** Packs an authenticated admin into the value carried by the session cookie. */
    public static String session(String clientId, String token) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(clientId.getBytes(StandardCharsets.UTF_8))
                + "." + token;
    }
}

package dev.orwell.secrets.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or whole controller) as requiring an admin caller — a token the
 * <em>admin</em> auth deployment accepts. Enforced by {@link SecretsRoleInterceptor} before the
 * handler runs: a token only the client deployment knows is a 403, an unknown one a 401.
 *
 * <p>Handlers that need the caller's identity, not just the gate, take
 * {@code @RequestAttribute(SecretsRoleInterceptor.CALLER_ATTRIBUTE) AuthenticationContext} — the
 * guard has already resolved it, so reading it costs no second round trip to the auth server.
 *
 * <p>This is the secrets-manager twin of {@code server-bootstrap}'s {@code @RequireAuthentication},
 * which cannot serve here: that guard reads the shared request-scoped {@code AuthenticationContext},
 * which is built from the client deployment, so an admin token would never authenticate against it.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}

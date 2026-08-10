package dev.orwell.secrets.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or whole controller) as requiring an accessor caller — a token the
 * <em>client</em> auth deployment accepts. Enforced by {@link SecretsRoleInterceptor}: an admin-only
 * token is a 403, an unknown one a 401. See {@link RequireAdmin} for the rest of the contract.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAccessor {
}

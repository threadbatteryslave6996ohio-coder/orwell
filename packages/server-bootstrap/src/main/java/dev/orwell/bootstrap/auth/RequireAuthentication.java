package dev.orwell.bootstrap.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or whole controller) as requiring an authenticated caller. Requests
 * without a valid {@code X-Client-Id} + bearer token are rejected with
 * {@code 401 {"error":"authentication required"}} before the handler (and body parsing) runs.
 *
 * <p>Endpoints that need the caller's identity beyond the yes/no gate (e.g. comparing the
 * authenticated clientId against a payload) still inject {@code AuthenticationContext} themselves.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuthentication {
}

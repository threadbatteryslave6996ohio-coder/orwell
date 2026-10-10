package dev.orwell.objectstorage.proxy;

import dev.orwell.auth.http.client.HttpAuthenticationException;

import java.util.Map;

/**
 * How a failed call to an auth server becomes an {@link AuthServerClient.AuthCallResult}.
 *
 * <p>{@link AuthServerClient} and {@link AdminAuthClient} both make these calls and both audit
 * them, and the classification has to agree between them: a failure the server answered keeps that
 * answer's status, and a failure it never answered — a timeout, a refused connection, anything
 * without a status — is a 503, because the caller's own request was not the thing that went wrong.
 * Written once so the two cannot drift into disagreeing about which is which.
 *
 * <p>The success paths are deliberately not shared: a login returns a token, a create returns an
 * identity, and folding those together would take more explaining than the duplication cost.
 */
final class AuthCalls {

    private AuthCalls() {
    }

    /**
     * @param event        the audit prefix, e.g. {@code authserver.login} — {@code .response_error}
     *                     or {@code .error} is appended
     * @param clientId     the identity the call was about, for the audit line
     * @param resultClientId what the returned result should carry; the callers differ on this, and
     *                     it is not always {@code clientId}
     */
    static AuthServerClient.AuthCallResult failure(
            FileAuditLogger audit, String event, String clientId, String resultClientId, Exception exception) {
        if (exception instanceof HttpAuthenticationException authFailure && authFailure.statusCode() != null) {
            audit.write(event + ".response_error",
                    Map.of("clientId", clientId, "statusCode", authFailure.statusCode()));
            return new AuthServerClient.AuthCallResult(false, authFailure.statusCode(), resultClientId, null);
        }
        audit.write(event + ".error",
                Map.of("clientId", clientId, "error", String.valueOf(exception.getMessage())));
        return new AuthServerClient.AuthCallResult(false, 503, resultClientId, null);
    }
}

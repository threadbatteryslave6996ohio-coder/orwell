package dev.orwell.auth.http.client;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;

import java.util.Objects;

/**
 * Caches a bearer token from the auth server for a single client identity and refreshes it on
 * demand. Two modes: when a client secret is configured the session can {@link #refresh()} by
 * logging in again (e.g. after a 401); otherwise it serves a fixed initial token and cannot
 * refresh. All state changes are synchronized, so one session is safe to share across request
 * threads.
 *
 * <p>A session belongs to one workspace, because a {@code clientId} only identifies an identity
 * within one. The constructor that omits it uses {@link AuthWorkspaces#DEFAULT}.
 */
public final class ClientAuthSession {
    private final String authServerUrl;
    private final String workspace;
    private final String clientId;
    private final String clientSecret;
    private final Logger logger;
    private String clientToken;

    public ClientAuthSession(String authServerUrl, String clientId, String clientSecret, String initialToken) {
        this(authServerUrl, AuthWorkspaces.DEFAULT, clientId, clientSecret, initialToken);
    }

    public ClientAuthSession(String authServerUrl, String workspace, String clientId, String clientSecret, String initialToken) {
        this(authServerUrl, workspace, clientId, clientSecret, initialToken, new ConsoleLogger("auth-http-client"));
    }

    /**
     * The logger is only used for the login this session makes when a token has to be refreshed.
     *
     * <p>There is deliberately no {@code (url, clientId, secret, token, logger)} overload to pair
     * with the workspace-less constructor above: its parameters would be five strings against four
     * strings and a {@code Logger}, and a call passing {@code null} for the token would match both.
     * Callers that want a logger name their workspace — {@link AuthWorkspaces#DEFAULT} if they have
     * not got one.
     */
    public ClientAuthSession(
            String authServerUrl,
            String workspace,
            String clientId,
            String clientSecret,
            String initialToken,
            Logger logger
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.authServerUrl = normalizedOrNull(authServerUrl);
        this.workspace = AuthWorkspaces.orDefault(workspace);
        this.clientId = normalized(clientId, "clientId");
        this.clientSecret = normalizedOrNull(clientSecret);
        this.clientToken = normalizedOrNull(initialToken);
    }

    public String clientId() {
        return clientId;
    }

    /** The workspace this session's identity lives in. */
    public String workspace() {
        return workspace;
    }

    public synchronized String token() {
        if (clientToken == null) {
            if (!canRefresh()) {
                throw new IllegalStateException("An initial token is required when no client secret is configured for refresh.");
            }
            refresh();
        }
        return clientToken;
    }

    public synchronized boolean canRefresh() {
        return clientSecret != null;
    }

    public synchronized boolean hasToken() {
        return clientToken != null;
    }

    public synchronized String refresh() {
        if (!canRefresh()) {
            throw new IllegalStateException("A client secret is required to request a fresh token from the auth server.");
        }
        if (authServerUrl == null) {
            throw new IllegalStateException("An auth server URL is required to request a fresh token from the auth server.");
        }

        HttpAuthenticationStrategy authClient =
                new HttpAuthenticationStrategy(authServerUrl, workspace, logger);
        LoginHttpResponse response = authClient.login(clientId, clientSecret);
        clientToken = tokenFrom(response);
        return clientToken;
    }

    /**
     * Reads the bearer token out of a successful login response.
     *
     * <p>An auth server that answers 200 with an empty body, or with a body that omits
     * {@code token}, is breaking its own contract rather than reporting a caller mistake — and
     * {@link LoginHttpResponse#token()} is a nullable record component, so such a response parses
     * cleanly and only fails where the value is used. That used to surface as a
     * {@code NullPointerException} indistinguishable from a bug: callers that already handle a
     * failed login dropped the work they were holding instead of retrying it. Report it as an
     * authentication failure so it travels the same path as every other login failure.
     */
    private static String tokenFrom(LoginHttpResponse response) {
        String token = response == null ? null : response.token();
        if (token == null || token.trim().isEmpty()) {
            throw new HttpAuthenticationException("Auth server returned a login response with no token.");
        }
        return token.trim();
    }

    public synchronized boolean refreshIfUnauthorized(int statusCode) {
        if (statusCode != 401 || !canRefresh()) {
            return false;
        }

        refresh();
        return true;
    }

    private static String normalized(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be blank.");
        }
        return normalized;
    }

    private static String normalizedOrNull(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

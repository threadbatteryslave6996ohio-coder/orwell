package dev.orwell.clients.core;

import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.auth.http.client.HttpAuthenticationException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class ClipboardApiClient {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final ClientAuthSession authSession;
    private final Duration requestTimeout;
    private final AuthRefreshListener refreshListener;

    public ClipboardApiClient(URI endpoint, ClientAuthSession authSession, Duration requestTimeout) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                endpoint, authSession, requestTimeout, AuthRefreshListener.NONE);
    }

    public ClipboardApiClient(
            HttpClient httpClient,
            URI endpoint,
            ClientAuthSession authSession,
            Duration requestTimeout,
            AuthRefreshListener refreshListener
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.authSession = Objects.requireNonNull(authSession, "authSession");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.refreshListener = Objects.requireNonNull(refreshListener, "refreshListener");
    }

    public URI endpoint() {
        return endpoint;
    }

    public HttpResponse<String> create(ClipboardEntry entry) throws IOException, InterruptedException {
        String body = ClipboardJson.write(entry);
        return sendWithAuthRetry(token -> HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Client-Id", authSession.clientId())
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    /**
     * Sends a liveness heartbeat to {@code heartbeatUri}. Carries the same client identity and
     * bearer token as a clipboard write (and the same 401-refresh retry), so the server can log
     * the beat against an authenticated client. The body is an empty JSON object: the server
     * derives the client id from the token, not the payload.
     */
    public HttpResponse<String> heartbeat(URI heartbeatUri) throws IOException, InterruptedException {
        return sendWithAuthRetry(token -> HttpRequest.newBuilder(heartbeatUri)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Client-Id", authSession.clientId())
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build());
    }

    public HttpResponse<String> get(URI uri) throws IOException, InterruptedException {
        return sendWithAuthRetry(token -> HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("X-Client-Id", authSession.clientId())
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
    }

    private HttpResponse<String> sendWithAuthRetry(RequestFactory requestFactory)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(requestFactory.create(currentToken()));
        if (response.statusCode() != 401 || !authSession.canRefresh()) {
            return response;
        }

        refreshListener.beforeRefresh();
        try {
            authSession.refresh();
            refreshListener.afterRefresh();
        } catch (RuntimeException exception) {
            refreshListener.refreshFailed(exception);
            throw asAuthenticationFailure("Could not refresh the bearer token after HTTP 401.", exception);
        }
        return send(requestFactory.create(currentToken()));
    }

    /**
     * Reads the bearer token for a request. {@link ClientAuthSession#token()} logs in lazily when it
     * holds no token yet, so this is an auth boundary and not a plain getter.
     */
    private String currentToken() {
        try {
            return authSession.token();
        } catch (RuntimeException exception) {
            throw asAuthenticationFailure("Could not obtain a bearer token for the request.", exception);
        }
    }

    /**
     * Presents every failure raised at the auth boundary as an {@link HttpAuthenticationException}.
     * {@link ClientAuthSession} reports a missing token or a missing auth server URL with
     * {@code IllegalStateException}, and a blank token from the auth server with
     * {@code IllegalArgumentException}. Left unclassified, those reached callers as bare runtime
     * exceptions that no caller could distinguish from a bug — and the desktop clients dropped the
     * clipboard entry they were holding instead of writing it to the offline log.
     */
    private static HttpAuthenticationException asAuthenticationFailure(
            String message, RuntimeException exception) {
        return exception instanceof HttpAuthenticationException authenticationFailure
                ? authenticationFailure
                : new HttpAuthenticationException(message, exception);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest create(String token);
    }

    public interface AuthRefreshListener {
        AuthRefreshListener NONE = new AuthRefreshListener() {
        };

        default void beforeRefresh() {
        }

        default void afterRefresh() {
        }

        default void refreshFailed(RuntimeException exception) {
        }
    }
}

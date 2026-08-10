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

        notifyListener(refreshListener::beforeRefresh);
        try {
            authSession.refresh();
        } catch (RuntimeException exception) {
            // Every failure is reported to the listener, including the ones not reclassified below:
            // beforeRefresh has already written a "started" audit record, and leaving it without a
            // matching outcome would silently lose the end of the auth trail.
            notifyListener(() -> refreshListener.refreshFailed(exception));
            throw authFailure(exception, "Could not refresh the bearer token after HTTP 401.");
        }
        notifyListener(refreshListener::afterRefresh);
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
            throw authFailure(exception, "Could not obtain a bearer token for the request.");
        }
    }

    /**
     * Classifies a failure from {@link ClientAuthSession} for the two paths that obtain a token —
     * the lazy login in {@link #currentToken()} and the 401 refresh in
     * {@link #sendWithAuthRetry(RequestFactory)}. Both classify identically, so both come here
     * rather than keeping two copies of the rule in sync by hand.
     *
     * <p>Only the credential failures the session actually raises are reclassified: a missing token
     * or a missing auth server URL ({@code IllegalStateException}), and a malformed auth server URL
     * ({@code IllegalArgumentException}, out of the {@code RestClient} builder). A login response
     * with no token is already an {@link HttpAuthenticationException} and passes through untouched.
     * Left unclassified, those reached callers as bare runtime exceptions no caller could
     * distinguish from a bug, and the desktop clients dropped the clipboard entry they were holding
     * instead of writing it to the offline log. The rule stays deliberately narrow for the same
     * reason {@code AuthenticationStrategyConfiguration}'s does: any OTHER runtime exception here is
     * a genuine bug and must stay loud rather than masquerade as an endless auth outage that quietly
     * diverts every entry offline.
     */
    private static RuntimeException authFailure(RuntimeException exception, String message) {
        return exception instanceof IllegalStateException || exception instanceof IllegalArgumentException
                ? new HttpAuthenticationException(message, exception)
                : exception;
    }

    /**
     * Runs an {@link AuthRefreshListener} callback. The listener is an audit side-channel — on Linux
     * it writes to the offline log and the console — so a failure there must not decide the outcome
     * of the request, and above all must not cost the caller the clipboard entry it is holding.
     */
    private static void notifyListener(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException ignored) {
            // The audit sink is broken; dropping its record beats losing the caller's entry.
        }
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

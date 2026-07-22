package dev.orwell.clients.core;

import dev.orwell.auth.http.client.ClientAuthSession;

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
        HttpResponse<String> response = send(requestFactory.create(authSession.token()));
        if (response.statusCode() != 401 || !authSession.canRefresh()) {
            return response;
        }

        refreshListener.beforeRefresh();
        try {
            authSession.refresh();
            refreshListener.afterRefresh();
        } catch (RuntimeException exception) {
            refreshListener.refreshFailed(exception);
            throw exception;
        }
        return send(requestFactory.create(authSession.token()));
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

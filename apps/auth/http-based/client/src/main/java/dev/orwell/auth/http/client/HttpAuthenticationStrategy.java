package dev.orwell.auth.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.orwell.auth.AuthenticationContext;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.api.CheckTokenHttpRequest;
import dev.orwell.auth.http.api.CheckTokenHttpResponse;
import dev.orwell.auth.http.api.CreateIdentityHttpRequest;
import dev.orwell.auth.http.api.CreateIdentityHttpResponse;
import dev.orwell.auth.http.api.LoginHttpRequest;
import dev.orwell.auth.http.api.LoginHttpResponse;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.HttpStatusCode;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Talks to one workspace of an auth server. The workspace is the first path segment of every
 * route, so a strategy is bound to it at construction: the constructors that do not name one use
 * {@link AuthWorkspaces#DEFAULT}, which is where every caller that predates workspaces was already
 * pointing.
 */
public final class HttpAuthenticationStrategy implements AuthenticationStrategy {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final String workspace;

    public HttpAuthenticationStrategy(String baseUrl) {
        this(baseUrl, AuthWorkspaces.DEFAULT);
    }

    /**
     * Records its calls to the console, because a caller that has said nothing about logging
     * should still be able to see whether the auth server was reached. Every constructor taking a
     * {@link Logger} is the one to prefer: it puts these calls in the process's own stream.
     */
    public HttpAuthenticationStrategy(String baseUrl, String workspace) {
        this(baseUrl, workspace, new ConsoleLogger("auth-http-client"));
    }

    public HttpAuthenticationStrategy(String baseUrl, Logger logger) {
        this(baseUrl, AuthWorkspaces.DEFAULT, logger);
    }

    public HttpAuthenticationStrategy(String baseUrl, String workspace, Logger logger) {
        this(RestClient.builder()
                .baseUrl(baseUrl)
                // Spring picks a request factory off the classpath; naming the JDK one explicitly is
                // what lets the client underneath it be the wrapped one. Same transport as before.
                .requestFactory(requestFactory(logger))
                .messageConverters(converters -> converters.add(new MappingJackson2HttpMessageConverter(objectMapper())))
                .build(), workspace);
    }

    /**
     * Both timeouts matter more here than anywhere else in the repo: this client is what the shared
     * 401 guard calls, so {@code /tokens/check} runs on <em>every authenticated request to every
     * service</em>. Unbounded, an auth server that accepts connections and then stops answering
     * does not fail those requests — it parks each one on a servlet thread until the pool is gone,
     * turning one sick service into every service being down. The payloads here are small JSON, so
     * a read timeout costs nothing legitimate.
     */
    private static JdkClientHttpRequestFactory requestFactory(Logger logger) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                LoggingHttpClient.wrap(
                        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), logger));
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /** Carries the time module: a create-identity response has an {@code Instant} in it. */
    private static ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return objectMapper;
    }

    public HttpAuthenticationStrategy(RestClient restClient) {
        this(restClient, AuthWorkspaces.DEFAULT);
    }

    public HttpAuthenticationStrategy(RestClient restClient, String workspace) {
        this.restClient = restClient;
        this.workspace = requireValidWorkspace(workspace);
    }

    /** The workspace this strategy authenticates against. */
    public String workspace() {
        return workspace;
    }

    public LoginHttpResponse login(String clientId, String secret) {
        return post(route("/login"), new LoginHttpRequest(clientId, secret), LoginHttpResponse.class, "login");
    }

    /**
     * Provisions an identity in this strategy's workspace.
     *
     * <p>The credentials are an admin's: the server accepts this call only for a token issued to an
     * identity in the {@code admin} workspace, whichever workspace the identity is being created
     * in. Callers hold that token however they already hold one — {@link ClientAuthSession} is the
     * usual answer, since it can log in again when the server stops accepting it.
     */
    public CreateIdentityHttpResponse createIdentity(String adminClientId, String adminToken, String clientId, String secret) {
        return post(
                route("/identities"),
                new CreateIdentityHttpRequest(clientId, secret),
                CreateIdentityHttpResponse.class,
                "identity creation",
                headers -> {
                    headers.header("X-Client-Id", adminClientId);
                    headers.header("Authorization", "Bearer " + adminToken);
                });
    }

    @Override
    public boolean isTokenValidForClient(String clientId, String token) {
        return authenticate(clientId, token).authenticated();
    }

    @Override
    public AuthenticationContext authenticate(String clientId, String token) {
        CheckTokenHttpResponse response = post(
                route("/tokens/check"), new CheckTokenHttpRequest(clientId, token), CheckTokenHttpResponse.class, "token check");

        if (response == null || !response.valid() || !clientId.equals(response.clientId())) {
            return AuthenticationContext.unauthenticated();
        }
        return AuthenticationContext.authenticated(response.clientId());
    }

    private String route(String path) {
        return "/" + workspace + path;
    }

    private static String requireValidWorkspace(String workspace) {
        String candidate = AuthWorkspaces.orDefault(workspace);
        if (!AuthWorkspaces.isValid(candidate)) {
            throw new IllegalArgumentException("Not a usable auth workspace name: " + workspace);
        }
        return candidate;
    }

    private <T> T post(String uri, Object body, Class<T> responseType, String action) {
        return post(uri, body, responseType, action, request -> {
        });
    }

    /**
     * Every auth call fails the same two ways, so they share one path: an error status becomes an
     * {@link HttpAuthenticationException} carrying that status, and a transport failure becomes one
     * carrying the cause. Keeping the endpoints on separate copies of this meant they could drift
     * into reporting the same failure differently.
     *
     * <p>The status handler's exception is not a {@link RestClientException}, so it travels past the
     * catch below rather than being rewrapped as a transport failure.
     */
    private <T> T post(
            String uri,
            Object body,
            Class<T> responseType,
            String action,
            Consumer<RestClient.RequestBodySpec> customizer
    ) {
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(uri);
            customizer.accept(request);
            return request
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (failedRequest, response) -> {
                        throw new HttpAuthenticationException(
                                "Auth server rejected %s with HTTP %d".formatted(action, response.getStatusCode().value()),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (RestClientException exception) {
            throw new HttpAuthenticationException("Cannot complete %s with auth server.".formatted(action), exception);
        }
    }
}

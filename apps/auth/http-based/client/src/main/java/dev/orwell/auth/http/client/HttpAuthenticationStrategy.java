package dev.orwell.auth.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.AuthenticationContext;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.api.CheckTokenHttpRequest;
import dev.orwell.auth.http.api.CheckTokenHttpResponse;
import dev.orwell.auth.http.api.LoginHttpRequest;
import dev.orwell.auth.http.api.LoginHttpResponse;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class HttpAuthenticationStrategy implements AuthenticationStrategy {
    private final RestClient restClient;

    public HttpAuthenticationStrategy(String baseUrl) {
        this(RestClient.builder()
                .baseUrl(baseUrl)
                .messageConverters(converters -> converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper())))
                .build());
    }

    public HttpAuthenticationStrategy(RestClient restClient) {
        this.restClient = restClient;
    }

    public LoginHttpResponse login(String clientId, String secret) {
        return post("/login", new LoginHttpRequest(clientId, secret), LoginHttpResponse.class, "login");
    }

    @Override
    public boolean isTokenValidForClient(String clientId, String token) {
        return authenticate(clientId, token).authenticated();
    }

    @Override
    public AuthenticationContext authenticate(String clientId, String token) {
        CheckTokenHttpResponse response = post(
                "/tokens/check", new CheckTokenHttpRequest(clientId, token), CheckTokenHttpResponse.class, "token check");

        if (response == null || !response.valid() || !clientId.equals(response.clientId())) {
            return AuthenticationContext.unauthenticated();
        }
        return AuthenticationContext.authenticated(response.clientId());
    }

    /**
     * Both auth calls fail the same two ways, so they share one path: an error status becomes an
     * {@link HttpAuthenticationException} carrying that status, and a transport failure becomes one
     * carrying the cause. Keeping the two endpoints on separate copies of this meant they could
     * drift into reporting the same failure differently.
     *
     * <p>The status handler's exception is not a {@link RestClientException}, so it travels past the
     * catch below rather than being rewrapped as a transport failure.
     */
    private <T> T post(String uri, Object body, Class<T> responseType, String action) {
        try {
            return restClient.post()
                    .uri(uri)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
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

package dev.orwell.auth.http.client;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        try {
            return restClient
                    .post()
                    .uri("/login")
                    .body(new LoginHttpRequest(clientId, secret))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new HttpAuthenticationException("Auth server rejected login with HTTP " + response.getStatusCode().value());
                    })
                    .body(LoginHttpResponse.class);
        } catch (RestClientException exception) {
            throw new HttpAuthenticationException("Cannot login with auth server.", exception);
        }
    }

    @Override
    public boolean isTokenValidForClient(String clientId, String token) {
        try {
            CheckTokenHttpResponse response = restClient.post()
                    .uri("/tokens/check")
                    .body(new CheckTokenHttpRequest(clientId, token))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, serverResponse) -> {
                        throw new HttpAuthenticationException("Auth server rejected token check with HTTP " + serverResponse.getStatusCode().value());
                    })
                    .body(CheckTokenHttpResponse.class);

            return response != null && response.valid() && clientId.equals(response.clientId());
        } catch (RestClientException exception) {
            throw new HttpAuthenticationException("Cannot check token with auth server.", exception);
        }
    }
}

package dev.clippy.auth.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.clippy.auth.api.CheckTokenRequest;
import dev.clippy.auth.api.CheckTokenResponse;
import dev.clippy.auth.api.LoginRequest;
import dev.clippy.auth.api.LoginResponse;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class ClippyAuthClient {
    private final RestClient restClient;

    public ClippyAuthClient(String baseUrl) {
        this(RestClient.builder()
                .baseUrl(baseUrl)
                .messageConverters(converters -> converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper())))
                .build());
    }

    public ClippyAuthClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public LoginResponse login(String clientId, String secret) {
        try {
            return restClient.post()
                    .uri("/login")
                    .body(new LoginRequest(clientId, secret))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new AuthClientException("Auth server rejected login with HTTP " + response.getStatusCode().value());
                    })
                    .body(LoginResponse.class);
        } catch (RestClientException exception) {
            throw new AuthClientException("Cannot login with auth server.", exception);
        }
    }

    public boolean isTokenValidForClient(String clientId, String token) {
        try {
            CheckTokenResponse response = restClient.post()
                    .uri("/tokens/check")
                    .body(new CheckTokenRequest(clientId, token))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, serverResponse) -> {
                        throw new AuthClientException("Auth server rejected token check with HTTP " + serverResponse.getStatusCode().value());
                    })
                    .body(CheckTokenResponse.class);

            return response != null && response.valid() && clientId.equals(response.clientId());
        } catch (RestClientException exception) {
            throw new AuthClientException("Cannot check token with auth server.", exception);
        }
    }
}

package dev.orwell.bucket.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AuthServerClient {
    private final HttpAuthenticationStrategy authClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FileAuditLogger audit;
    private final String identityProvisioningKey;

    @Autowired
    public AuthServerClient(ProxyProperties properties, FileAuditLogger audit) {
        this(new HttpAuthenticationStrategy(properties.authServer().baseUrl()),
                RestClient.builder().baseUrl(properties.authServer().baseUrl()).build(),
                properties.authServer().identityProvisioningKey(),
                audit);
    }

    AuthServerClient(HttpAuthenticationStrategy authClient, RestClient restClient, String identityProvisioningKey, FileAuditLogger audit) {
        this.authClient = authClient;
        this.restClient = restClient;
        this.identityProvisioningKey = identityProvisioningKey;
        this.audit = audit;
    }

    public AuthCallResult login(String clientId, String secret) {
        try {
            audit.write("authserver.login.send", java.util.Map.of("clientId", clientId));
            LoginHttpResponse response = authClient.login(clientId, secret);
            audit.write("authserver.login.response", java.util.Map.of("clientId", clientId, "statusCode", 200));
            return new AuthCallResult(response.token() != null && !response.token().isBlank(), 200, response.clientId(), response.token());
        } catch (HttpAuthenticationException exception) {
            Integer statusCode = exception.statusCode();
            if (statusCode != null) {
                audit.write("authserver.login.response_error", java.util.Map.of("clientId", clientId, "statusCode", statusCode));
                return new AuthCallResult(false, statusCode, null, null);
            }
            audit.write("authserver.login.error", java.util.Map.of("clientId", clientId, "error", exception.getMessage()));
            return new AuthCallResult(false, 503, null, null);
        } catch (Exception exception) {
            audit.write("authserver.login.error", java.util.Map.of("clientId", clientId, "error", exception.getMessage()));
            return new AuthCallResult(false, 503, null, null);
        }
    }

    public AuthCallResult createIdentity(String clientId, String secret) {
        try {
            audit.write("authserver.identity.create.send", java.util.Map.of("clientId", clientId));
            var response = restClient.post()
                    .uri("/identities")
                    .header("X-Identity-Provisioning-Key", identityProvisioningKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AuthCreateIdentityRequest(clientId, secret))
                    .retrieve()
                    .toEntity(String.class);
            audit.write("authserver.identity.create.response", java.util.Map.of("clientId", clientId, "statusCode", response.getStatusCode().value()));
            return new AuthCallResult(response.getStatusCode().is2xxSuccessful(), response.getStatusCode().value(), clientId, null);
        } catch (RestClientResponseException exception) {
            audit.write("authserver.identity.create.response_error", java.util.Map.of("clientId", clientId, "statusCode", exception.getStatusCode().value()));
            return new AuthCallResult(false, exception.getStatusCode().value(), clientId, null);
        } catch (Exception exception) {
            audit.write("authserver.identity.create.error", java.util.Map.of("clientId", clientId, "error", exception.getMessage()));
            return new AuthCallResult(false, 503, clientId, null);
        }
    }

    public record AuthCreateIdentityRequest(String clientId, String secret) {}
    public record AuthCallResult(boolean success, int statusCode, String clientId, String token) {}
}

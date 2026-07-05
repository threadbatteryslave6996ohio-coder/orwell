package dev.orwell.secrets.config;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Configuration
@EnableConfigurationProperties(SecretsServerProperties.class)
public class AuthClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthenticationStrategy.class)
    AuthenticationStrategy secretsHttpAuthenticationStrategy(SecretsServerProperties properties) {
        HttpAuthenticationStrategy httpStrategy = new HttpAuthenticationStrategy(properties.baseUrl());
        return (clientId, token) -> {
            try {
                return httpStrategy.isTokenValidForClient(clientId, token);
            } catch (HttpAuthenticationException exception) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Cannot reach auth server.",
                        exception
                );
            }
        };
    }
}

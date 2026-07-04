package dev.orwell.server;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthServerProperties.class)
public class AuthClientConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthenticationStrategy.class)
    AuthenticationStrategy clippyHttpAuthenticationStrategy(AuthServerProperties properties) {
        HttpAuthenticationStrategy httpStrategy = new HttpAuthenticationStrategy(properties.baseUrl());
        return (clientId, token) -> {
            try {
                return httpStrategy.isTokenValidForClient(clientId, token);
            } catch (HttpAuthenticationException exception) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Cannot reach auth server.",
                        exception
                );
            }
        };
    }
}

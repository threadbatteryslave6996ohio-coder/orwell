package dev.orwell.secrets.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "secrets.auth")
public record SecretsServerProperties(
        String baseUrl
) {
}

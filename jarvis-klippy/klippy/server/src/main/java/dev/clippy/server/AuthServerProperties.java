package dev.clippy.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clippy.auth")
public record AuthServerProperties(
        String baseUrl
) {
}

package dev.clippy.server;

import dev.clippy.auth.client.ClippyAuthClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthServerProperties.class)
public class AuthClientConfiguration {
    @Bean
    ClippyAuthClient clippyAuthClient(AuthServerProperties properties) {
        return new ClippyAuthClient(properties.baseUrl());
    }
}

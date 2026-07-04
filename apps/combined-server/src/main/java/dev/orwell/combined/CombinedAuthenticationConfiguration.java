package dev.orwell.combined;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class CombinedAuthenticationConfiguration {
    @Bean
    @Primary
    AuthenticationStrategy combinedHttpAuthenticationStrategy(
            @Value("${clippy.auth.base-url}") String authBaseUrl
    ) {
        return new HttpAuthenticationStrategy(authBaseUrl);
    }
}

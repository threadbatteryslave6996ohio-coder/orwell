package dev.orwell.bucket.proxy;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuthenticationConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthenticationStrategy.class)
    AuthenticationStrategy jarvisHttpAuthenticationStrategy(ProxyProperties properties) {
        return new HttpAuthenticationStrategy(properties.authServer().baseUrl());
    }
}

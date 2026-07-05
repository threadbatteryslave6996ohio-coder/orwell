package dev.orwell.bootstrap;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthenticationStrategyConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuthenticationStrategy.class)
    AuthenticationStrategy sharedHttpAuthenticationStrategy(
            @Value("${clippy.auth.base-url:http://localhost:8081}") String authBaseUrl
    ) {
        return new HttpAuthenticationStrategy(authBaseUrl);
    }
}

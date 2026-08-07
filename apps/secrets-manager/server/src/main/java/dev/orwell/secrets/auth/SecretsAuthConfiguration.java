package dev.orwell.secrets.auth;

import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the two auth deployments this service checks against: admins live in one, ordinary clients
 * in the other, and which server accepts a token is what grants the role.
 */
@Configuration
public class SecretsAuthConfiguration {

    @Bean
    AdminAuth adminAuth(@Value("${secrets.admin-auth-base-url}") String adminAuthBaseUrl) {
        return new AdminAuth(new HttpAuthenticationStrategy(adminAuthBaseUrl));
    }

    @Bean
    ClientAuth clientAuth(@Value("${orwell.auth.base-url:http://localhost:8081}") String clientAuthBaseUrl) {
        return new ClientAuth(new HttpAuthenticationStrategy(clientAuthBaseUrl));
    }
}

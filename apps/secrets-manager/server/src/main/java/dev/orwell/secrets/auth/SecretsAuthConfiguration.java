package dev.orwell.secrets.auth;

import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.secrets.service.AuthValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Binds the two auth deployments this service checks against: admins live in one, ordinary clients
 * in the other, and which server accepts a token is what grants the role. Also registers the guard
 * that enforces {@link RequireAdmin} / {@link RequireAccessor} against them.
 */
@Configuration
public class SecretsAuthConfiguration {

    @Bean
    WebMvcConfigurer secretsRoleConfigurer(AuthValidator authValidator) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SecretsRoleInterceptor(authValidator));
            }
        };
    }

    @Bean
    AdminAuth adminAuth(@Value("${secrets.admin-auth-base-url}") String adminAuthBaseUrl) {
        return new AdminAuth(new HttpAuthenticationStrategy(adminAuthBaseUrl));
    }

    @Bean
    ClientAuth clientAuth(@Value("${orwell.auth.base-url:http://localhost:8081}") String clientAuthBaseUrl) {
        return new ClientAuth(new HttpAuthenticationStrategy(clientAuthBaseUrl));
    }
}

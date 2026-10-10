package dev.orwell.secrets.auth;

import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.secrets.service.AuthValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import dev.orwell.logging.Logger;

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
    AdminAuth adminAuth(@Value("${secrets.admin-auth-base-url}") String adminAuthBaseUrl, Logger logger) {
        return new AdminAuth(new HttpAuthenticationStrategy(adminAuthBaseUrl, logger));
    }

    /**
     * The client strategy honours {@code AUTH_WORKSPACE}. Built with the base-url-only constructor
     * it silently validated against {@code def} however the key was set, so the module published a
     * setting it then ignored. Admin identities are a separate deployment and keep their own
     * workspace — see object-storage-proxy, which carries a distinct key for that side.
     */
    @Bean
    ClientAuth clientAuth(
            @Value("${orwell.auth.base-url:http://localhost:8081}") String clientAuthBaseUrl,
            @Value("${orwell.auth.workspace:}") String clientAuthWorkspace,
            Logger logger) {
        return new ClientAuth(
                new HttpAuthenticationStrategy(clientAuthBaseUrl, clientAuthWorkspace, logger));
    }
}

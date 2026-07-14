package dev.orwell.bootstrap;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.http.HttpServletRequest;

@Configuration
public class AuthenticationStrategyConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuthenticationStrategy.class)
    AuthenticationStrategy sharedHttpAuthenticationStrategy(
            @Value("${clippy.auth.base-url:http://localhost:8081}") String authBaseUrl
    ) {
        return new HttpAuthenticationStrategy(authBaseUrl);
    }

    @Bean
    @Scope(WebApplicationContext.SCOPE_REQUEST)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    AuthenticationContext requestAuthenticationContext(
            HttpServletRequest request,
            AuthenticationStrategy authenticationStrategy
    ) {
        String clientId = request.getHeader("X-Client-Id");
        String authorization = request.getHeader("Authorization");
        String token = dev.orwell.auth.BearerToken.extract(authorization);
        if (clientId == null || clientId.isBlank() || token == null) {
            return AuthenticationContext.unauthenticated();
        }
        try {
            return authenticationStrategy.authenticate(clientId, token);
        } catch (HttpAuthenticationException exception) {
            // An unreachable/failing auth service degrades to "unauthenticated" (a 401 at the
            // controllers), not a 500 from every endpoint that touches the context. Deliberately
            // narrow: any OTHER RuntimeException from a strategy is a genuine bug and must surface
            // as a 500 rather than masquerade as endless bad-credential 401s.
            System.err.println("Auth service check failed; treating request as unauthenticated: "
                    + exception.getMessage());
            return AuthenticationContext.unauthenticated();
        }
    }
}

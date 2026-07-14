package dev.orwell.bootstrap;

import dev.orwell.auth.AuthenticationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Shared web contracts for every server app: the invalid-JSON 400 envelope
 * ({@link InvalidJsonBodyAdvice}) and the {@link RequireAuthentication} 401 guard.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebContractConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvalidJsonBodyAdvice.class)
    InvalidJsonBodyAdvice invalidJsonBodyAdvice() {
        return new InvalidJsonBodyAdvice();
    }

    @Bean
    WebMvcConfigurer requireAuthenticationConfigurer(
            ObjectProvider<AuthenticationContext> authenticationContextProvider
    ) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new RequireAuthenticationInterceptor(authenticationContextProvider));
            }
        };
    }
}

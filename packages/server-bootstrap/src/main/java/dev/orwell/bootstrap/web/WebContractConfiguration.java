package dev.orwell.bootstrap.web;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.bootstrap.auth.RequireAuthenticationInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Shared web contracts for every server app: the invalid-JSON 400 envelope
 * ({@link InvalidJsonBodyAdvice}), the {@link RequireAuthentication} 401 guard, and the
 * {@link RequestIdFilter} that gives every request an id to be traced by.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebContractConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvalidJsonBodyAdvice.class)
    InvalidJsonBodyAdvice invalidJsonBodyAdvice() {
        return new InvalidJsonBodyAdvice();
    }

    /**
     * Registered at {@link Ordered#HIGHEST_PRECEDENCE} because everything downstream — the auth
     * guard, the controllers, any client call they make — should already be inside the id's scope.
     * A filter that ran later would leave the earliest records of a request unattributed, which are
     * exactly the ones worth having when a request is rejected before it reaches a controller.
     */
    @Bean
    @ConditionalOnMissingBean(RequestIdFilter.class)
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
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

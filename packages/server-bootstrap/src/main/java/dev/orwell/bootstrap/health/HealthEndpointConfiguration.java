package dev.orwell.bootstrap.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the shared {@code /health} endpoint on every servlet app. Apps contribute extra
 * health payload by declaring {@link HealthDetailsProvider} beans; an app can replace the whole
 * endpoint by declaring its own {@link SharedHealthController} bean.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HealthEndpointConfiguration {

    @Bean
    @ConditionalOnMissingBean(SharedHealthController.class)
    SharedHealthController sharedHealthController(ObjectProvider<HealthDetailsProvider> detailsProviders) {
        return new SharedHealthController(detailsProviders);
    }
}

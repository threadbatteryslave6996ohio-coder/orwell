package dev.orwell.bootstrap;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HealthEndpointConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "jarvisHealthDetailsProvider")
    HealthDetailsProvider jarvisHealthDetailsProvider() {
        return Map::of;
    }

    @Bean
    @ConditionalOnMissingBean(name = "clippyHealthDetailsProvider")
    HealthDetailsProvider clippyHealthDetailsProvider() {
        return Map::of;
    }

    @Bean
    @ConditionalOnMissingBean(name = "secretsHealthDetailsProvider")
    HealthDetailsProvider secretsHealthDetailsProvider() {
        return Map::of;
    }
}

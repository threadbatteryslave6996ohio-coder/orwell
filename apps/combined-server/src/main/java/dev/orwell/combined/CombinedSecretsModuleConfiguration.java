package dev.orwell.combined;

import dev.orwell.secrets.SecretsManagerApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@ComponentScan(
        basePackageClasses = SecretsManagerApplication.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = SecretsManagerApplication.class
        )
)
class CombinedSecretsModuleConfiguration {
}

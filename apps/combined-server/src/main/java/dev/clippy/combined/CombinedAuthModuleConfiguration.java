package dev.clippy.combined;

import dev.clippy.auth.AuthController;
import dev.clippy.auth.ClippyAuthServerApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@ComponentScan(
        basePackageClasses = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ClippyAuthServerApplication.class
        )
)
class CombinedAuthModuleConfiguration {
}

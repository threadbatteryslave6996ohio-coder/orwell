package dev.orwell.combined;

import dev.orwell.auth.http.server.controller.AuthController;
import dev.orwell.auth.http.server.ClippyAuthServerApplication;
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

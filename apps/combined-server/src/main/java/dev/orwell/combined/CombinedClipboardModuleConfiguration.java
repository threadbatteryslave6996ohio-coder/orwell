package dev.orwell.combined;

import dev.orwell.server.ClippyServerApplication;
import dev.orwell.server.ClipboardEntryController;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

@Configuration
@ComponentScan(
        basePackageClasses = ClipboardEntryController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ClippyServerApplication.class
        )
)
class CombinedClipboardModuleConfiguration {
}

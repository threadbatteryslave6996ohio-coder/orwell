package dev.orwell.bootstrap.launch;

import dev.orwell.logging.LogFiles;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.Objects;

public final class SpringServerBootstrap {
    private SpringServerBootstrap() {
    }

    /**
     * Shared server startup shell. The logging directory is derived from the same
     * {@code logging.file.name} property that Spring receives.
     */
    public static ConfigurableApplicationContext start(
            Class<?> applicationClass,
            Map<String, Object> properties,
            String propertySourceName
    ) {
        Objects.requireNonNull(applicationClass, "applicationClass");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(propertySourceName, "propertySourceName");

        Object loggingFileName = properties.get("logging.file.name");
        if (loggingFileName != null) {
            LogFiles.configureDirectoryFromLogFile(loggingFileName.toString());
        }

        SpringApplication application = new SpringApplication(applicationClass);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(propertySourceName, properties)));
        return application.run();
    }
}

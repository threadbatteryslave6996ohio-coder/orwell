package dev.orwell.bootstrap.launch;

import dev.orwell.logging.CustomLogger;
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
            Runnable startupHook,
            String propertySourceName
    ) {
        Objects.requireNonNull(applicationClass, "applicationClass");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(propertySourceName, "propertySourceName");

        Object loggingFileName = properties.get("logging.file.name");
        if (loggingFileName != null) {
            CustomLogger.configureDirectoryFromLogFile(loggingFileName.toString());
        }
        if (startupHook != null) {
            startupHook.run();
        }

        SpringApplication application = new SpringApplication(applicationClass);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(propertySourceName, properties)));
        return application.run();
    }

    public static ConfigurableApplicationContext start(
            Class<?> applicationClass,
            Map<String, Object> properties,
            String propertySourceName
    ) {
        return start(applicationClass, properties, null, propertySourceName);
    }
}

package dev.orwell.bootstrap;

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
     * Shared server startup shell: configure the logging directory from {@code loggingFileName},
     * run any server-specific pre-start hook, then boot Spring with {@code properties}.
     */
    public static ConfigurableApplicationContext start(
            Class<?> applicationClass,
            String loggingFileName,
            Runnable beforeRun,
            Map<String, Object> properties,
            String propertySourceName
    ) {
        CustomLogger.configureDirectoryFromLogFile(loggingFileName);
        if (beforeRun != null) {
            beforeRun.run();
        }
        return run(applicationClass, properties, propertySourceName);
    }

    public static ConfigurableApplicationContext start(
            Class<?> applicationClass,
            String loggingFileName,
            Map<String, Object> properties,
            String propertySourceName
    ) {
        return start(applicationClass, loggingFileName, null, properties, propertySourceName);
    }

    public static ConfigurableApplicationContext run(
            Class<?> applicationClass,
            Map<String, Object> properties,
            String propertySourceName
    ) {
        Objects.requireNonNull(applicationClass, "applicationClass");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(propertySourceName, "propertySourceName");

        SpringApplication application = new SpringApplication(applicationClass);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(propertySourceName, properties)));
        return application.run();
    }
}

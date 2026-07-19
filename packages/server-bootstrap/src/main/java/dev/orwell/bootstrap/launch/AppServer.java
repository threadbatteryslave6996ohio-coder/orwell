package dev.orwell.bootstrap.launch;

import dev.orwell.env.Env;
import dev.orwell.env.EnvValidationException;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Shared launcher for standalone Spring server applications. */
public final class AppServer {
    private final Class<?> applicationClass;
    private final String name;
    private final AppServerEnv environment;

    public AppServer(Class<?> applicationClass, String name, AppServerEnv environment) {
        this.applicationClass = Objects.requireNonNull(applicationClass, "applicationClass");
        this.name = Objects.requireNonNull(name, "name");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public ConfigurableApplicationContext start(String[] args) throws IOException {
        return start(args, SpringEnvLoaders.standard());
    }

    public ConfigurableApplicationContext start(Map<String, String> source) {
        Objects.requireNonNull(source, "source");
        return start(environment.schema().from(source));
    }

    ConfigurableApplicationContext start(String[] args, SpringEnvLoader envLoader) throws IOException {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(envLoader, "envLoader");
        Map<String, String> source = envLoader.load(args.clone());
        return start(source);
    }

    public ConfigurableApplicationContext start(Env env) {
        Objects.requireNonNull(env, "env");
        Map<String, Object> properties = new java.util.LinkedHashMap<>(environment.springProperties(env));
        properties.put("orwell.app.name", name);
        return SpringServerBootstrap.start(applicationClass, properties, name);
    }

    public void runOrExit(String[] args) {
        try {
            start(args);
        } catch (IOException | EnvValidationException | IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }
}

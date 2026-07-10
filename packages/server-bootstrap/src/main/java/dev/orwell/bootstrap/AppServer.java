package dev.orwell.bootstrap;

import dev.orwell.env.Env;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvValidationException;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public record AppServer(
        EnvSchema envSchema,
        Function<Env, ConfigurableApplicationContext> starter
) {
    public AppServer {
        Objects.requireNonNull(envSchema, "envSchema");
        Objects.requireNonNull(starter, "starter");
    }

    public ConfigurableApplicationContext start(String[] args) throws IOException {
        return start(args, SpringEnvLoaders.standard());
    }

    public ConfigurableApplicationContext start(Map<String, String> source) {
        Objects.requireNonNull(source, "source");
        return start(envSchema.from(source));
    }

    ConfigurableApplicationContext start(String[] args, SpringEnvLoader envLoader) throws IOException {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(envLoader, "envLoader");
        Map<String, String> source = envLoader.load(args.clone());
        return start(source);
    }

    public ConfigurableApplicationContext start(Env env) {
        return starter.apply(env);
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

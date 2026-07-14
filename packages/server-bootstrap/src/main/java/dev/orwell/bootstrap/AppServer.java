package dev.orwell.bootstrap;

import dev.orwell.env.Env;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvValidationException;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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

    /**
     * Descriptor for a standard Spring server app. Collapses the previously per-app
     * launcher + {@code start(Env)} + {@code start(Map)} triplet into one declaration:
     *
     * <pre>{@code
     * public static final AppServer SERVER = AppServer.spring(MyApplication.class)
     *         .name("my-app")                       // logger bean name + property source name
     *         .envs(MyEnvs.ENV)
     *         .properties(MyEnvs::springProperties)
     *         .loggingFile(env -> env.get(MyEnvs.LOGGING_FILE_NAME))  // optional
     *         .beforeRun(MyApplication::logStartupNotice)             // optional
     *         .build();
     * }</pre>
     *
     * The {@code name} is also published as the {@code orwell.app.name} property, which drives the
     * shared {@link LoggerConfiguration} logger bean.
     */
    public static Builder spring(Class<?> applicationClass) {
        return new Builder(applicationClass);
    }

    public static final class Builder {
        private final Class<?> applicationClass;
        private String name;
        private EnvSchema envSchema;
        private Function<Env, Map<String, Object>> properties;
        private Function<Env, String> loggingFile;
        private Consumer<Env> beforeRun;

        Builder(Class<?> applicationClass) {
            this.applicationClass = Objects.requireNonNull(applicationClass, "applicationClass");
        }

        /** App name: used as the logger name ({@code orwell.app.name}) and the property-source name. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder envs(EnvSchema envSchema) {
            this.envSchema = envSchema;
            return this;
        }

        /** Maps the validated {@link Env} onto Spring properties. The only genuinely per-app piece. */
        public Builder properties(Function<Env, Map<String, Object>> properties) {
            this.properties = properties;
            return this;
        }

        /** Optional: log-file name used to configure the logging directory before Spring boots. */
        public Builder loggingFile(Function<Env, String> loggingFile) {
            this.loggingFile = loggingFile;
            return this;
        }

        /** Optional: hook run after logging is configured but before the Spring context starts. */
        public Builder beforeRun(Consumer<Env> beforeRun) {
            this.beforeRun = beforeRun;
            return this;
        }

        public AppServer build() {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(envSchema, "envSchema");
            Objects.requireNonNull(properties, "properties");
            Function<Env, Map<String, Object>> propertiesFn = properties;
            Function<Env, String> loggingFileFn = loggingFile;
            Consumer<Env> beforeRunFn = beforeRun;
            String appName = name;
            Class<?> appClass = applicationClass;

            return new AppServer(envSchema, env -> {
                Map<String, Object> props = new HashMap<>(propertiesFn.apply(env));
                props.putIfAbsent("orwell.app.name", appName);
                Runnable hook = beforeRunFn == null ? null : () -> beforeRunFn.accept(env);
                if (loggingFileFn != null) {
                    return SpringServerBootstrap.start(appClass, loggingFileFn.apply(env), hook, props, appName);
                }
                if (hook != null) {
                    hook.run();
                }
                return SpringServerBootstrap.run(appClass, props, appName);
            });
        }
    }
}

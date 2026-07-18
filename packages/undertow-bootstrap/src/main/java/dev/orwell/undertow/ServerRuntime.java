package dev.orwell.undertow;

import dev.orwell.env.Env;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.http.EnvLoader;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/** Framework-neutral environment loading and server-engine selection. */
public final class ServerRuntime {
    public enum Engine { SPRING, UNDERTOW }

    private static final String DEFAULT_REMOTE_URL = "http://localhost:8080/v1/env";

    private ServerRuntime() {
    }

    @FunctionalInterface
    public interface EngineStarter {
        void start(Env environment) throws Exception;
    }

    public static void runOrExit(
            String[] args,
            EnvSchema schema,
            EngineStarter spring,
            EngineStarter undertow
    ) {
        try {
            Map<String, String> source = loadEnvironment(args);
            Env environment = schema.from(source);
            switch (engine(source)) {
                case SPRING -> spring.start(environment);
                case UNDERTOW -> undertow.start(environment);
            }
        } catch (Exception exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    public static Map<String, String> loadEnvironment(String[] args) throws IOException {
        if (args.length == 0 || "file".equals(args[0])) {
            return EnvFiles.load();
        }
        return switch (args[0]) {
            case "url" -> EnvLoader.load("url");
            case "remote", "--remote" -> EnvLoader.fetchRemote(
                    args.length > 1 ? args[1] : DEFAULT_REMOTE_URL);
            default -> throw new IllegalArgumentException(
                    "Unknown env loader: " + args[0] + " (expected 'file', 'url', or 'remote')");
        };
    }

    public static Engine engine(Map<String, String> environment) {
        String configured = environment.getOrDefault("SERVER_ENGINE", "spring")
                .trim().toLowerCase(Locale.ROOT);
        return switch (configured) {
            case "spring" -> Engine.SPRING;
            case "undertow" -> Engine.UNDERTOW;
            default -> throw new IllegalArgumentException(
                    "SERVER_ENGINE must be 'spring' or 'undertow', got: " + configured);
        };
    }
}

package dev.orwell.bootstrap;

import dev.orwell.env.EnvFiles;
import dev.orwell.env.http.EnvLoader;

import java.io.IOException;
import java.util.Map;

final class SpringEnvLoaders {
    private static final String DEFAULT_REMOTE_URL = "http://localhost:8080/v1/env";

    private SpringEnvLoaders() {
    }

    public static SpringEnvLoader standard() {
        return args -> {
            if (args.length == 0) {
                return EnvFiles.load();
            }

            return switch (args[0]) {
                case "file" -> EnvFiles.load();
                case "url" -> EnvLoader.load("url");
                case "remote", "--remote" -> EnvLoader.fetchRemote(
                        args.length > 1 ? args[1] : DEFAULT_REMOTE_URL);
                default -> throw new IllegalArgumentException(
                        "Unknown env loader: " + args[0] + " (expected 'file', 'url', or 'remote')");
            };
        };
    }

    public static SpringEnvLoader fileOnly() {
        return args -> EnvFiles.load();
    }
}

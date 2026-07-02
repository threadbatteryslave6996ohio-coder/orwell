package dev.clippy.utils.envmanager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class EnvFiles {
    private static final String DOTENV_FILE = ".env";

    private EnvFiles() {
    }

    public static Map<String, String> load() throws IOException {
        return load(Path.of("").toAbsolutePath());
    }

    public static Map<String, String> load(Path startDirectory) throws IOException {
        Map<String, String> values = new HashMap<>(loadDotenv(startDirectory));
        System.getenv().forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                values.put(name, value);
            }
        });
        return values;
    }

    public static Map<String, String> loadDotenvOnly(Path startDirectory) throws IOException {
        return loadDotenv(startDirectory);
    }

    public static Map<String, String> loadFile(Path dotenvFile) throws IOException {
        return loadDotenv(dotenvFile);
    }

    public static Map<String, String> loadRequiredFile(Path dotenvFile) throws IOException {
        if (!Files.isRegularFile(dotenvFile)) {
            throw new IllegalStateException("Env file is not present: " + dotenvFile.toAbsolutePath().normalize());
        }
        return loadDotenv(dotenvFile);
    }

    private static Map<String, String> loadDotenv(Path startDirectory) throws IOException {
        Path path = Files.isRegularFile(startDirectory) ? startDirectory : findDotenv(startDirectory);
        if (path == null) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = trimmed.substring(0, separator).trim();
            String value = unquote(trimmed.substring(separator + 1).trim());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static Path findDotenv(Path startDirectory) {
        Path directory = startDirectory;
        while (directory != null) {
            Path candidate = directory.resolve(DOTENV_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}

package dev.orwell.env;

import dev.orwell.logging.CustomLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class EnvSnapshotLogger {
    private final String name;
    private final boolean consoleEnabled;
    private final CustomLogger fileLogger;

    private EnvSnapshotLogger(String name, boolean consoleEnabled, boolean fileEnabled) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("name cannot be blank.");
        }
        this.name = trimmed;
        this.consoleEnabled = consoleEnabled;
        this.fileLogger = fileEnabled ? new CustomLogger(this.name) : null;
    }

    public static EnvSnapshotLogger consoleOnly(String name) {
        return new EnvSnapshotLogger(name, true, false);
    }

    public static EnvSnapshotLogger fileOnly(String name) {
        return new EnvSnapshotLogger(name, false, true);
    }

    public static EnvSnapshotLogger consoleAndFile(String name) {
        return new EnvSnapshotLogger(name, true, true);
    }

    public void log(EnvSchema schema, Env env) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(env, "env");

        List<EnvOption<?>> options = new ArrayList<>(schema.options());
        options.sort(Comparator.comparing(EnvOption::name));

        List<String> entries = new ArrayList<>();
        for (EnvOption<?> option : options) {
            if (!env.has(option)) {
                continue;
            }
            entries.add(option.name() + "=" + formatValue(option.name(), env.get(option)));
        }

        String message = entries.isEmpty()
                ? "Loaded 0 environment values."
                : "Loaded " + entries.size() + " environment values: " + String.join(", ", entries);

        if (consoleEnabled) {
            System.out.printf("[%s] %s%n", name, message);
        }
        if (fileLogger != null) {
            fileLogger.log(message);
        }
    }

    private static String formatValue(String name, Object value) {
        if (isSensitive(name)) {
            return "[redacted]";
        }
        if (value instanceof String stringValue) {
            return "\"" + escape(stringValue) + "\"";
        }
        return String.valueOf(value);
    }

    private static boolean isSensitive(String name) {
        String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("SECRET")
                || normalized.contains("TOKEN")
                || normalized.contains("PASSWORD")
                || normalized.contains("CREDENTIAL")
                || normalized.contains("PRIVATE_KEY")
                || normalized.contains("API_KEY")
                || normalized.contains("ACCESS_KEY")
                || normalized.endsWith("_KEY");
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}

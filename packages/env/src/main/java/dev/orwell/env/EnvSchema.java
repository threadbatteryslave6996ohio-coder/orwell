package dev.orwell.env;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EnvSchema {
    private final Map<String, EnvOption<?>> optionsByName;

    EnvSchema(Collection<EnvOption<?>> options) {
        Map<String, EnvOption<?>> copied = new LinkedHashMap<>();
        for (EnvOption<?> option : options) {
            EnvOptions.putUnique(copied, option);
        }
        this.optionsByName = Map.copyOf(copied);
    }

    public static EnvClassBuilder builder() {
        return EnvClassBuilder.schema();
    }

    public Env fromSystem() {
        return from(System.getenv());
    }

    public Env from(Map<String, String> source) {
        return from(source, null);
    }

    public Env fromSystem(EnvSnapshotLogger logger) {
        return from(System.getenv(), logger);
    }

    public Env from(Map<String, String> source, EnvSnapshotLogger logger) {
        Objects.requireNonNull(source, "source");
        Map<EnvOption<?>, Object> values = new LinkedHashMap<>();

        for (EnvOption<?> option : optionsByName.values()) {
            String rawValue = source.get(option.name());
            if (rawValue == null || rawValue.isBlank()) {
                addMissingValue(option, values);
                continue;
            }

            values.put(option, parse(option, rawValue));
        }

        Env env = new Env(this, values);
        if (logger != null) {
            logger.log(this, env);
        }
        return env;
    }

    boolean contains(EnvOption<?> option) {
        return optionsByName.get(option.name()) == option;
    }

    Collection<EnvOption<?>> options() {
        return optionsByName.values();
    }

    private static <T> void addMissingValue(EnvOption<T> option, Map<EnvOption<?>, Object> values) {
        if (option.hasDefaultValue()) {
            values.put(option, option.rawDefaultValue());
            return;
        }
        if (option.required()) {
            throw new EnvValidationException("Missing required environment variable: " + option.name());
        }
    }

    private static <T> T parse(EnvOption<T> option, String rawValue) {
        try {
            return option.type().parse(rawValue);
        } catch (RuntimeException exception) {
            throw new EnvValidationException(
                    "Invalid environment variable " + option.name() + ": expected " + option.type().description(),
                    exception
            );
        }
    }
}

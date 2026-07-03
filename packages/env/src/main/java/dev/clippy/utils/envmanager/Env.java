package dev.clippy.utils.envmanager;

import java.util.Map;
import java.util.Objects;

public final class Env {
    private final EnvSchema schema;
    private final Map<EnvOption<?>, Object> values;

    Env(EnvSchema schema, Map<EnvOption<?>, Object> values) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.values = Map.copyOf(values);
    }

    public boolean has(EnvOption<?> option) {
        requireKnownOption(option);
        return values.containsKey(option);
    }

    public <T> T get(EnvOption<T> option) {
        requireKnownOption(option);
        Object value = values.get(option);
        if (value == null) {
            throw new IllegalStateException("Missing optional environment value: " + option.name());
        }
        return option.type().valueClass().cast(value);
    }

    private void requireKnownOption(EnvOption<?> option) {
        Objects.requireNonNull(option, "option");
        if (!schema.contains(option)) {
            throw new IllegalArgumentException("Environment option is not part of this schema: " + option.name());
        }
    }
}

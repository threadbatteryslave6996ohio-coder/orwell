package dev.clippy.utils.envmanager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class EnvClassBuilder {
    private final Map<String, EnvOption<?>> options = new LinkedHashMap<>();

    private EnvClassBuilder() {
    }

    public static EnvClassBuilder schema() {
        return new EnvClassBuilder();
    }

    public <T> EnvOption<T> required(String name, EnvType<T> type) {
        EnvOption<T> option = EnvOption.required(name, type);
        add(option);
        return option;
    }

    public <T> EnvOption<T> optional(String name, EnvType<T> type) {
        EnvOption<T> option = EnvOption.optional(name, type);
        add(option);
        return option;
    }

    public <T> EnvOption<T> optional(String name, EnvType<T> type, T defaultValue) {
        EnvOption<T> option = EnvOption.optional(name, type, defaultValue);
        add(option);
        return option;
    }

    public EnvSchema build() {
        return new EnvSchema(options.values());
    }

    private void add(EnvOption<?> option) {
        Objects.requireNonNull(option, "option");
        EnvOptions.putUnique(options, option);
    }
}

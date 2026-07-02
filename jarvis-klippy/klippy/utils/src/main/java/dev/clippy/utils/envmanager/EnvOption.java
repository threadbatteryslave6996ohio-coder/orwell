package dev.clippy.utils.envmanager;

import dev.clippy.utils.Strings;

import java.util.Objects;
import java.util.Optional;

public final class EnvOption<T> {
    private final String name;
    private final EnvType<T> type;
    private final boolean required;
    private final T defaultValue;
    private final boolean hasDefaultValue;

    private EnvOption(String name, EnvType<T> type, boolean required, T defaultValue, boolean hasDefaultValue) {
        this.name = validateName(name);
        this.type = Objects.requireNonNull(type, "type");
        this.required = required;
        this.defaultValue = defaultValue;
        this.hasDefaultValue = hasDefaultValue;
    }

    public static <T> EnvOption<T> required(String name, EnvType<T> type) {
        return new EnvOption<>(name, type, true, null, false);
    }

    public static <T> EnvOption<T> optional(String name, EnvType<T> type) {
        return new EnvOption<>(name, type, false, null, false);
    }

    public static <T> EnvOption<T> optional(String name, EnvType<T> type, T defaultValue) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        return new EnvOption<>(name, type, false, defaultValue, true);
    }

    public String name() {
        return name;
    }

    public EnvType<T> type() {
        return type;
    }

    public boolean required() {
        return required;
    }

    public Optional<T> defaultValue() {
        return hasDefaultValue ? Optional.of(defaultValue) : Optional.empty();
    }

    boolean hasDefaultValue() {
        return hasDefaultValue;
    }

    T rawDefaultValue() {
        return defaultValue;
    }

    private static String validateName(String name) {
        return Strings.requireNonBlank(name, "Environment option name");
    }
}

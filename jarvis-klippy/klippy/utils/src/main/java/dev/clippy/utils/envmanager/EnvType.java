package dev.clippy.utils.envmanager;

import java.util.Objects;
import java.util.function.Function;

public final class EnvType<T> {
    private final Class<T> valueClass;
    private final String description;
    private final Function<String, T> parser;

    private EnvType(Class<T> valueClass, String description, Function<String, T> parser) {
        this.valueClass = Objects.requireNonNull(valueClass, "valueClass");
        this.description = Objects.requireNonNull(description, "description");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public static <T> EnvType<T> of(Class<T> valueClass, String description, Function<String, T> parser) {
        return new EnvType<>(valueClass, description, parser);
    }

    public static EnvType<String> string() {
        return of(String.class, "string", value -> value);
    }

    public static EnvType<Integer> integer() {
        return of(Integer.class, "integer", Integer::parseInt);
    }

    public static EnvType<Long> longInteger() {
        return of(Long.class, "long", Long::parseLong);
    }

    public static EnvType<Boolean> bool() {
        return of(Boolean.class, "boolean", EnvType::parseBoolean);
    }

    public Class<T> valueClass() {
        return valueClass;
    }

    public String description() {
        return description;
    }

    public T parse(String rawValue) {
        return parser.apply(rawValue);
    }

    private static Boolean parseBoolean(String rawValue) {
        String normalized = rawValue.trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "y", "on" -> true;
            case "false", "0", "no", "n", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean value: " + rawValue);
        };
    }
}

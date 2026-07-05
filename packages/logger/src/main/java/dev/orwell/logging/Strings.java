package dev.orwell.logging;

import java.util.Objects;

public final class Strings {
    private Strings() {
    }

    public static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

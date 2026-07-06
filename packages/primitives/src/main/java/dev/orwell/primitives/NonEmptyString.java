package dev.orwell.primitives;

import java.util.Objects;

public class NonEmptyString {

    private final String value;

    public NonEmptyString(String value) {
        this(value, "value must not be null or blank");
    }

    public NonEmptyString(String value, String errorMessage) {
        requireNonBlank(value, errorMessage);
        this.value = value;
    }

    private static void requireNonBlank(String value, String errorMessage) {
        if (value == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    public String value() {
        return value;
    }

}

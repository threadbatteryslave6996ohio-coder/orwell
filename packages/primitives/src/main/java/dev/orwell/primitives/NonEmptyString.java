package dev.orwell.primitives;

import java.util.Objects;

public class NonEmptyString {

    private final String value;

    public NonEmptyString(String value) {
        this(value, null);
    }

    private boolean IsEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public NonEmptyString(String value, String errorMessage) {
        if (IsEmpty(value)) {
            throw new IllegalArgumentException(
                    errorMessage != null ? errorMessage : "value must not be null or blank");
        }
        this.value = value;
    }

    public NonEmptyString(String value, boolean exit, String errorMessage ) {
        if (IsEmpty(value)) {
            if (errorMessage != null) {
                System.err.println(errorMessage);
            }
            System.exit(1);
        }
        this.value = value;
    }

    public String value() {
        return value;
    }

}

package dev.clippy.utils;

/** Small internal string helpers shared across the utils module. */
public final class Strings {
    private Strings() {
    }

    /**
     * Returns {@code value} trimmed, or throws {@link IllegalArgumentException} naming
     * {@code field} when {@code value} is null or blank.
     */
    public static String requireNonBlank(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return trimmed;
    }
}

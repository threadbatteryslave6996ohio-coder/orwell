package dev.orwell.keycycler;

import java.util.Objects;

/**
 * One credential out of the pool, and the position it holds in it.
 *
 * <p>The position is not bookkeeping — it is the <em>only</em> thing about a key that is ever
 * logged, recorded, or reported on {@code /health}. A key is a secret and a proxy that rotates
 * keys is exactly the process with all of them in memory at once, so the number is what makes
 * "which key got rate-limited?" answerable without the answer being the key.
 *
 * <p>{@link #toString()} is overridden for the same reason: the default for a record prints every
 * component, and this one would print the secret into the first stack trace or debug line that
 * touched it.
 */
public record ApiKey(int position, String value) {
    public ApiKey {
        if (position < 1) {
            throw new IllegalArgumentException("position counts from 1: " + position);
        }
        Objects.requireNonNull(value, "value");
    }

    /** How this key is named in a log line: {@code key #2}. Never the value. */
    public String label() {
        return "key #" + position;
    }

    @Override
    public String toString() {
        return label();
    }
}

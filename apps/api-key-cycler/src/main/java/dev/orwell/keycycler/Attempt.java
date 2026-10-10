package dev.orwell.keycycler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One request's journey through the forward loop: which try this is, and a scratch pad
 * {@link ApiKeyFilter} uses to carry its choice from {@code before} to {@code after}.
 *
 * <p><strong>The scratch pad is why this class exists.</strong> The filter is a singleton — one
 * bean serving every thread — so it cannot remember in a field which key it just handed out and
 * still be correct under load. It notes it here instead: one {@code Attempt} is created per inbound
 * request, is touched only by that request's thread, and is thrown away with it.
 *
 * <p>The number starts at 1 and is advanced by {@link ProxyEndpoint} alone.
 */
public final class Attempt {
    private final int max;
    private final Map<String, Object> notes = new LinkedHashMap<>();
    private int number = 1;

    public Attempt(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max attempts must be positive: " + max);
        }
        this.max = max;
    }

    /** Which try this is, counting from 1. */
    public int number() {
        return number;
    }

    /** How many tries this request gets in total, {@code CYCLER_MAX_ATTEMPTS}. */
    public int max() {
        return max;
    }

    /** True when no further attempt is available, whatever the filter asks for. */
    public boolean isLast() {
        return number >= max;
    }

    /** Remembers something for the matching {@code after}. Overwrites a note of the same name. */
    public void note(String name, Object value) {
        notes.put(Objects.requireNonNull(name, "name"), value);
    }

    /** What {@link #note(String, Object)} stored under {@code name}, or {@code null}. */
    public Object note(String name) {
        return notes.get(name);
    }

    /** Advances to the next try. Package-private: the retry budget is the endpoint's to spend. */
    void next() {
        number++;
    }
}

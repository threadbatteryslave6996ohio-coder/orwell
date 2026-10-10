package dev.orwell.keycycler;

import java.util.Objects;

/**
 * What {@link ApiKeyFilter} asks for once it has seen the upstream's answer: keep it, or try again
 * with the next key. The reason is not decoration — it is what the log line says about a request
 * that took more than one trip, so "why did this cost three upstream calls?" has an answer in the
 * trail rather than only in someone's memory of the configuration.
 */
public record Retry(boolean retry, String reason) {
    private static final Retry NO = new Retry(false, "");

    public Retry {
        Objects.requireNonNull(reason, "reason");
    }

    /** Keep this response. */
    public static Retry no() {
        return NO;
    }

    /** Forward again, up to the attempt budget. {@code reason} is logged, so name the cause. */
    public static Retry yes(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a retry must say why");
        }
        return new Retry(true, reason);
    }
}

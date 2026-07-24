package dev.orwell.liveness;

import java.io.IOException;
import java.time.Instant;

/** Delivers "client stopped beating" alerts. */
interface Alerter {
    /**
     * {@code REJECTED} is a terminal error (most 4xx) the caller should not retry for the same
     * payload; {@code FAILED} is transient (5xx, 408, 429) and worth retrying next check.
     */
    enum Outcome {
        DELIVERED,
        REJECTED,
        FAILED
    }

    boolean isEnabled();

    Outcome sendClientDown(String clientId, Instant lastSeen, int thresholdSeconds)
            throws IOException, InterruptedException;
}

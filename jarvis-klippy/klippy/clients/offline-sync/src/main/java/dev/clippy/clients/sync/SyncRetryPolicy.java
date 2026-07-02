package dev.clippy.clients.sync;

import java.time.Duration;

final class SyncRetryPolicy {
    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;

    SyncRetryPolicy(int maxRetries, Duration initialDelay, Duration maxDelay) {
        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
    }

    Duration delay(int retryNumber) {
        if (retryNumber < 1) {
            throw new IllegalArgumentException("Retry number must be positive.");
        }
        long multiplier = 1L << Math.min(retryNumber - 1, 30);
        Duration delay = initialDelay.multipliedBy(multiplier);
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    int nextFailure(int failures, String operation, Exception cause) {
        int next = failures + 1;
        if (next > maxRetries) {
            throw new IllegalStateException("Stopped after " + maxRetries + " retries while attempting to "
                    + operation + ": " + cause.getMessage(), cause);
        }
        return next;
    }
}

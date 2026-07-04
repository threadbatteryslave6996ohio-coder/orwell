package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;

public final class PollIntervalValidator {
    private PollIntervalValidator() {
    }

    public static long validate(long value, long minimumMs) {
        if (value < minimumMs) {
            throw new IllegalArgumentException(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS.name()
                    + " must be at least " + minimumMs + ".");
        }
        return value;
    }
}

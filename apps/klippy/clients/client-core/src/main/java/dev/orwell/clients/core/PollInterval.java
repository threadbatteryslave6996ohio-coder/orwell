package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;

public final class PollInterval {
    /**
     * Floor for {@code CLIPBOARD_POLL_INTERVAL_MS}. Below this the desktop clients spend
     * more time reading the clipboard than the user spends changing it.
     */
    public static final long MINIMUM_MS = 100L;

    private PollInterval() {
    }

    /**
     * Resolves the poll interval shared by the desktop clipboard clients, falling back to
     * the {@code CLIPBOARD_POLL_INTERVAL_MS} schema default when the variable is unset.
     *
     * <p>Values below {@link #MINIMUM_MS} are clamped to it and reported on stderr rather
     * than rejected: an out-of-range interval is a tuning mistake, not a reason to leave a
     * user's clipboard unsynced until they edit their {@code .env}.
     */
    public static long resolve(Env env) {
        long configured = env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS);
        if (configured < MINIMUM_MS) {
            System.err.println(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS.name() + "=" + configured
                    + " is below the " + MINIMUM_MS + " ms minimum; polling every " + MINIMUM_MS + " ms instead.");
            return MINIMUM_MS;
        }
        return configured;
    }
}

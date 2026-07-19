package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import dev.orwell.logging.CustomLogger;
import dev.orwell.logging.LogLevel;

import java.util.Map;

public final class PollInterval {
    /**
     * Floor for {@code CLIPBOARD_POLL_INTERVAL_MS}. Below this the desktop clients spend
     * more time reading the clipboard than the user spends changing it.
     */
    public static final long MINIMUM_MS = 100L;

    /** Exit status used when the configured interval is unusable. */
    static final int INVALID_INTERVAL_EXIT_CODE = 1;

    private static final CustomLogger LOGGER = new CustomLogger("klippy-client");

    private PollInterval() {
    }

    /**
     * Resolves the poll interval shared by the desktop clipboard clients, falling back to
     * the {@code CLIPBOARD_POLL_INTERVAL_MS} schema default when the variable is unset.
     *
     * <p>An interval below {@link #MINIMUM_MS} is fatal: the client reports it on stderr and
     * through the logger service, then exits. Starting anyway would either burn a CPU core
     * polling or run at an interval the operator did not ask for.
     */
    public static long resolve(Env env) {
        long configured = env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS);
        if (configured < MINIMUM_MS) {
            report(rejectionMessage(configured));
            System.exit(INVALID_INTERVAL_EXIT_CODE);
        }
        return configured;
    }

    static String rejectionMessage(long configured) {
        return ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS.name() + "=" + configured
                + " is below the " + MINIMUM_MS + " ms minimum; refusing to start.";
    }

    /**
     * Reports a fatal interval on stderr first, then to the logger service. Stderr goes
     * first because {@link CustomLogger} throws when its log directory is unwritable, and a
     * failure to log must not swallow the reason the client is exiting.
     */
    static void report(String message) {
        System.err.println(message);
        try {
            LOGGER.log(LogLevel.ERROR, message, Map.of());
        } catch (RuntimeException exception) {
            System.err.println("Could not write the log entry: " + exception.getMessage());
        }
    }
}

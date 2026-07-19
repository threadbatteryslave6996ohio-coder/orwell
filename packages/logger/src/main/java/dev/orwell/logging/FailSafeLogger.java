package dev.orwell.logging;

import java.util.Objects;

/**
 * Contains sink failures so a logging call can never fail its caller.
 *
 * <p>Sinks do real I/O and can fail for reasons that have nothing to do with the work being
 * logged — a full disk, a revoked permission, a rotated-away directory. Without this, a
 * request-handling path that logs unguarded turns such a failure into a failed request: the auth
 * server would answer HTTP 500 to a valid login because it could not append to a text file.
 *
 * <p>A dropped log line is a real cost, so the failure is reported once to standard error rather
 * than vanishing. That is the one place raw {@code System.err} is correct: it is the fallback for
 * the logging system itself being broken.
 */
public final class FailSafeLogger implements Logger {
    private final Logger delegate;
    private boolean reportedFailure;

    public FailSafeLogger(Logger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void log(LogEntry entry) {
        try {
            delegate.log(entry);
        } catch (RuntimeException failure) {
            reportOnce(failure);
        }
    }

    /** Reported once per instance: a broken sink usually fails on every call, and a flood helps nobody. */
    private synchronized void reportOnce(RuntimeException failure) {
        if (reportedFailure) {
            return;
        }
        reportedFailure = true;
        System.err.println("Logging sink failed; log entries are being dropped: " + failure);
    }
}

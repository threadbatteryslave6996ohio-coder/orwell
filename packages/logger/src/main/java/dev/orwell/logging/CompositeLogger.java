package dev.orwell.logging;

import java.util.List;
import java.util.Objects;

/**
 * Fans one entry out to several sinks. The servers use this to keep writing their durable
 * {@code logs/<app>.txt} file while still emitting to the console, so container log collectors
 * (which read stdout/stderr) do not go silent.
 *
 * <p>A failing sink must not swallow the entry for the others: exceptions from each delegate are
 * collected and rethrown once every sink has been given the entry.
 */
public final class CompositeLogger implements Logger {
    private final List<Logger> delegates;

    public CompositeLogger(Logger... delegates) {
        this(List.of(delegates));
    }

    public CompositeLogger(List<Logger> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        RuntimeException failure = null;
        for (Logger delegate : delegates) {
            try {
                delegate.log(entry);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

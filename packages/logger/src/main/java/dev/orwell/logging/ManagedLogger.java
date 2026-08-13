package dev.orwell.logging;

import java.util.List;
import java.util.Objects;

/**
 * A composed logger together with the sinks that have to be shut down with it.
 *
 * <p>{@link LokiLogger} owns a worker thread and an in-memory queue, so whoever builds it also has
 * to close it — but once it is wrapped in a {@link CompositeLogger} inside a {@link FailSafeLogger}
 * the caller no longer has a reference to close. Returning this instead of a bare {@link Logger}
 * keeps the shutdown reachable: Spring calls {@link #close()} on context shutdown, and a
 * standalone {@code main} can use it in try-with-resources or a shutdown hook.
 *
 * <p>It is still just a {@link Logger} to every call site; nothing injects this type.
 */
public final class ManagedLogger implements Logger, AutoCloseable {
    private final LoggerMode mode;
    private final Logger delegate;
    private final List<AutoCloseable> closeables;

    public ManagedLogger(LoggerMode mode, Logger delegate, List<AutoCloseable> closeables) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.closeables = List.copyOf(Objects.requireNonNull(closeables, "closeables"));
    }

    /** Which mode built this logger — worth reporting from a health endpoint. */
    public LoggerMode mode() {
        return mode;
    }

    @Override
    public void log(LogEntry entry) {
        delegate.log(entry);
    }

    /**
     * Flushes and stops every sink that needs it. Failures go to stderr rather than through the
     * logger: the sinks are the thing being torn down, so this is the same last-resort reporting
     * {@link FailSafeLogger} does, not a stray {@code System.err} call.
     */
    @Override
    public void close() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception failure) {
                System.err.println("Could not shut down a log sink cleanly: " + failure);
            }
        }
    }
}

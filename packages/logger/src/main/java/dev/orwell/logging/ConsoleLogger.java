package dev.orwell.logging;

import dev.orwell.primitives.NonEmptyString;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Writes entries to the console: WARN and ERROR to standard error, everything else to standard
 * output. This is the default sink for the desktop clients, which have no Spring context to inject
 * a file-backed logger from — they construct one in {@code main} and pass it down.
 */
public final class ConsoleLogger implements Logger {
    private final String name;
    private final PrintStream out;
    private final PrintStream err;

    public ConsoleLogger(String name) {
        this(name, System.out, System.err);
    }

    /** Streams are injectable so tests can capture output without touching the real console. */
    public ConsoleLogger(String name, PrintStream out, PrintStream err) {
        this.name = new NonEmptyString(name, "name cannot be blank").value();
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        streamFor(entry.level()).println(format(entry));
    }

    private String format(LogEntry entry) {
        return LogFormatter.format(name, entry.level(), entry.message(), entry.metadata());
    }

    private PrintStream streamFor(LogLevel level) {
        return level == LogLevel.WARN || level == LogLevel.ERROR ? err : out;
    }
}

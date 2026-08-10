package dev.orwell.insta;

import dev.orwell.env.Env;
import dev.orwell.logging.CompositeLogger;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.FailSafeLogger;
import dev.orwell.logging.JsonLogger;
import dev.orwell.logging.LogEntry;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LokiLogger;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The program's logger: which sinks it writes to, chosen from the environment.
 *
 * <p>Everything inside the program already takes a {@link Logger} and knows nothing about where
 * records end up — {@link dev.orwell.insta.apify.ApifyClient},
 * {@link dev.orwell.insta.instagram.InstagramService} and the cache are all constructor-injected.
 * This class is the one place a concrete sink is named, so changing what production does is a
 * change to configuration, not to code.
 *
 * <table>
 *   <caption>Sink selection</caption>
 *   <tr><th>Set</th><th>Adds</th></tr>
 *   <tr><td>nothing</td><td>human-readable console</td></tr>
 *   <tr><td>{@code LOKI_URL}</td><td>async batched push to Loki</td></tr>
 *   <tr><td>{@code LOGGING_FILE_NAME}</td><td>JSON lines to that file</td></tr>
 *   <tr><td>{@code INSTA_LOG_CONSOLE=false}</td><td>drops the console sink</td></tr>
 * </table>
 *
 * <p>Two things differ from the Spring services in this repo, both because this is a program a
 * person runs rather than a server that boots once.
 *
 * <p><b>The console sink writes to stderr, not stdout.</b> Results are stdout and nothing else is,
 * so {@code insta followers nasa --json | jq} stays parseable no matter how chatty logging gets.
 *
 * <p><b>An unset {@code LOKI_URL} is not warned about.</b> The Spring bean complains, because a
 * server with no log shipping is usually a misconfigured deployment. For a command someone runs
 * by hand, console-only is the ordinary case, and a warning on every invocation would be noise.
 *
 * <p>{@link #close()} matters more here than it does in a server: {@link LokiLogger} batches on a
 * <em>daemon</em> thread and flushes every couple of seconds, so a program that exits in under a
 * second would take its unsent records with it. Closing drains them. {@link InstaCli} closes this
 * in a try-with-resources, which is why the logger is built there and passed down.
 */
public final class InstaLogger implements Logger, AutoCloseable {
    static final String APP_NAME = "insta";

    private final Logger delegate;
    private final List<AutoCloseable> closeables;

    private InstaLogger(Logger delegate, List<AutoCloseable> closeables) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.closeables = List.copyOf(closeables);
    }

    /** Reads the sink choice out of {@link InstaEnvs}. */
    public static InstaLogger from(Env env, PrintStream console) {
        return create(
                console,
                env.get(InstaEnvs.INSTA_LOG_CONSOLE),
                env.get(InstaEnvs.LOKI_URL),
                env.get(InstaEnvs.LOKI_TENANT_ID),
                env.get(InstaEnvs.LOGGING_FILE_NAME));
    }

    /**
     * The same choice expressed as plain values, so a test can ask for one sink combination
     * without building an {@link Env}.
     *
     * <p>Every sink is optional and a bad one is skipped rather than fatal: an unwritable log file
     * must not be the reason a lookup does not happen. If that leaves nothing at all, records go
     * nowhere — which is what {@code INSTA_LOG_CONSOLE=false} with no other sink asks for.
     */
    public static InstaLogger create(
            PrintStream console,
            boolean consoleEnabled,
            String lokiUrl,
            String lokiTenantId,
            String logFileName) {
        List<Logger> sinks = new ArrayList<>();
        List<AutoCloseable> closeables = new ArrayList<>();
        ConsoleLogger reporter = new ConsoleLogger(APP_NAME, console, console);

        if (consoleEnabled) {
            sinks.add(reporter);
        }
        if (isSet(logFileName)) {
            try {
                sinks.add(new JsonLogger(Path.of(logFileName)));
            } catch (Exception exception) {
                reporter.warn("Could not open the log file; continuing without it.", Map.of(
                        "file", logFileName, "error", exception.toString()));
            }
        }
        if (isSet(lokiUrl)) {
            try {
                LokiLogger loki = new LokiLogger(APP_NAME, URI.create(lokiUrl), lokiTenantId);
                sinks.add(loki);
                closeables.add(loki);
            } catch (Exception exception) {
                reporter.warn("Could not start the Loki sink; continuing without it.", Map.of(
                        "url", lokiUrl, "error", exception.toString()));
            }
        }

        // FailSafeLogger last, so a sink that fails mid-run cannot take a lookup down with it.
        return new InstaLogger(new FailSafeLogger(new CompositeLogger(sinks)), closeables);
    }

    @Override
    public void log(LogEntry entry) {
        delegate.log(entry);
    }

    /** Drains anything a sink is holding. Safe to call once; {@link InstaCli} does exactly that. */
    @Override
    public void close() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception exception) {
                // Nothing useful is left to log to — the sinks are what is shutting down.
                continue;
            }
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}

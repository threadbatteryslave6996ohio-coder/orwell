package dev.orwell.insta;

import dev.orwell.env.Env;
import dev.orwell.logging.LoggerMode;
import dev.orwell.logging.LoggerSetup;
import dev.orwell.logging.ManagedLogger;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Maps insta's environment onto {@link LoggerSetup}, which is where every process in this repo
 * assembles its sinks.
 *
 * <p>This class used to build the sink tree itself, and had drifted: it never learned {@code
 * LOGGER}, so none of the repo's modes worked here, and it called the three-argument
 * {@link dev.orwell.logging.LokiLogger} constructor, so {@code loki-with-fallback} — the mode that
 * catches records Loki refused in a file — could not be reached from insta at all. Everything
 * below is now a translation into {@link LoggerSetup#create}; nothing here decides how a sink is
 * built.
 *
 * <p>Two insta-specific translations remain, both because this is a program a person runs rather
 * than a server that boots once:
 *
 * <p><b>The console sink writes to stderr, not stdout.</b> Results are stdout and nothing else is,
 * so {@code insta followers nasa --json | jq} stays parseable no matter how chatty logging gets.
 * That is the {@code console} stream {@link #from} is handed.
 *
 * <p><b>{@code INSTA_LOG_CONSOLE=false} drops the console.</b> {@link LoggerMode} has no value for
 * this on purpose — a server's console is what {@code docker logs} reads and must never be
 * configurable away — but a command someone runs in a terminal is the case the rule was not written
 * for. It is honoured by pointing the console sink at a discarded stream rather than by teaching
 * the shared factory a mode that only insta would ever set.
 *
 * <p>{@link ManagedLogger#close()} matters more here than in a server:
 * {@link dev.orwell.logging.LokiLogger} batches on a <em>daemon</em> thread and flushes every
 * couple of seconds, so a program that exits in under a second would take its unsent records with
 * it. {@link InstaCli} closes it in a try-with-resources, which is why the logger is built there
 * and passed down.
 */
public final class InstaLogger {
    static final String APP_NAME = "insta";

    /** Swallows what a disabled console would have printed. */
    private static final PrintStream DISCARD =
            new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);

    private InstaLogger() {
    }

    /** Reads the sink choice out of {@link InstaEnvs}. */
    public static ManagedLogger from(Env env, PrintStream console) {
        return create(
                console,
                console,
                env.get(InstaEnvs.INSTA_LOG_CONSOLE),
                env.get(InstaEnvs.LOGGER),
                env.get(InstaEnvs.LOKI_URL),
                env.get(InstaEnvs.LOKI_TENANT_ID),
                env.get(InstaEnvs.LOGGING_FILE_NAME));
    }

    /**
     * The same choice expressed as plain values, so a test can ask for one sink combination without
     * building an {@link Env}.
     *
     * <p>An unopenable sink is reported and skipped rather than being fatal — that is
     * {@link LoggerSetup}'s rule, and it is the one insta needs: a log file nobody can write must
     * not be the reason a paid lookup does not happen. A <em>contradiction</em> between configured
     * values still fails, so {@code LOGGER=loki} with no {@code LOKI_URL} stops the program instead
     * of pretending to ship records; {@link InstaCli} turns that into a configuration error and
     * exit code 2.
     *
     * <p>{@code diagnostics} is where that report goes, and it is deliberately <em>not</em> the
     * stream {@code consoleEnabled} switches off. Turning the console off silences records; it must
     * not also silence "this log file could not be opened", or a cron job with a bad path discards
     * everything and says nothing anywhere.
     */
    public static ManagedLogger create(
            PrintStream console,
            PrintStream diagnostics,
            boolean consoleEnabled,
            String configuredMode,
            String lokiUrl,
            String lokiTenantId,
            String logFileName) {
        LoggerMode mode = LoggerMode.parseOrDefault(configuredMode, defaultMode(lokiUrl, logFileName));
        PrintStream sink = consoleEnabled ? console : DISCARD;
        return LoggerSetup.create(
                APP_NAME, mode, lokiUrl, lokiTenantId, diskFile(logFileName), sink, sink, diagnostics);
    }

    /**
     * What insta does when {@code LOGGER} is unset. It is deliberately <em>not</em>
     * {@link LoggerSetup#defaultMode(String)}: on a server the file sink appears only where
     * {@code LOGGER} asks for one, but here {@code LOGGING_FILE_NAME} has always meant "also write
     * this file", and every existing insta configuration predates {@code LOGGER}. Deriving the mode
     * from which keys are set keeps all of them working unchanged; naming {@code LOGGER} overrides
     * the lot.
     */
    static LoggerMode defaultMode(String lokiUrl, String logFileName) {
        boolean loki = isSet(lokiUrl);
        boolean disk = isSet(logFileName);
        if (loki && disk) {
            return LoggerMode.BOTH;
        }
        if (loki) {
            return LoggerMode.LOKI;
        }
        return disk ? LoggerMode.DISK : LoggerMode.CONSOLE;
    }

    /**
     * insta names the file outright where a server names the directory holding Spring's log, so
     * {@code LOGGING_FILE_NAME} is used as given. Unset, the shared default applies — it is only
     * reached by an explicit {@code LOGGER=disk}, since {@link #defaultMode} does not pick a disk
     * mode without a filename.
     */
    private static Path diskFile(String logFileName) {
        return isSet(logFileName)
                ? Path.of(logFileName.trim())
                : LoggerSetup.defaultDiskFile(APP_NAME);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}

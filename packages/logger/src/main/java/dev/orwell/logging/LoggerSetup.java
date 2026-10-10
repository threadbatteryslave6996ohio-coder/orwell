package dev.orwell.logging;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the logger a process should use from configuration, so every server assembles its sinks
 * the same way instead of hand-rolling the same {@code if (LOKI_URL is set)} in each {@code main}.
 *
 * <p>The shape is always the same, and only the durable sinks vary with {@link LoggerMode}:
 *
 * <pre>
 *   FailSafeLogger( CompositeLogger( ConsoleLogger,      always — stdout is what docker logs reads
 *                                    JsonLogger,         disk / both
 *                                    LokiLogger ) )      loki / loki-with-fallback / both
 * </pre>
 *
 * <p>Two failure rules, drawn on purpose in different places:
 *
 * <ul>
 *   <li>A <strong>contradiction between configured values</strong> — asking for Loki without a
 *       {@code LOKI_URL} — fails startup. It costs nothing to detect, and the alternative is a
 *       deployment that believes it is shipping logs and is not.</li>
 *   <li>A <strong>sink that cannot be opened</strong> — an unwritable log file — is reported
 *       loudly on the console and skipped. Logging must not be the reason a server refuses to
 *       boot, and the records still reach the sinks that do work.</li>
 * </ul>
 *
 * <p>This class deliberately takes plain values rather than an {@code Env}: {@code packages/logger}
 * stays free of the env framework, so desktop clients and tests can call it with literals.
 */
public final class LoggerSetup {
    /** Extension for the file sinks, matching {@link JsonLogger}'s one-JSON-object-per-line format. */
    private static final String DISK_EXTENSION = ".jsonl";

    private LoggerSetup() {
    }

    /**
     * The main entry point: build from raw configuration, as an app reads it from the environment.
     *
     * @param appName       the app's name, used as the logger name and the Loki {@code app} label
     * @param configuredMode the {@code LOGGER} value; blank means {@link #defaultMode(String)}
     * @param lokiUrl       the {@code LOKI_URL} push endpoint, may be blank
     * @param lokiTenantId  the {@code LOKI_TENANT_ID} sent as {@code X-Scope-OrgID}, may be blank
     */
    public static ManagedLogger fromConfiguration(
            String appName,
            String configuredMode,
            String lokiUrl,
            String lokiTenantId
    ) {
        return fromConfiguration(appName, configuredMode, lokiUrl, lokiTenantId, System.out, System.err);
    }

    /** As {@link #fromConfiguration}, with the console streams injectable for tests. */
    public static ManagedLogger fromConfiguration(
            String appName,
            String configuredMode,
            String lokiUrl,
            String lokiTenantId,
            PrintStream out,
            PrintStream err
    ) {
        boolean configured = configuredMode != null && !configuredMode.isBlank();
        LoggerMode mode = configured ? LoggerMode.parse(configuredMode) : defaultMode(lokiUrl);
        ManagedLogger logger =
                create(appName, mode, lokiUrl, lokiTenantId, defaultDiskFile(appName), out, err);
        if (!configured && mode == LoggerMode.CONSOLE) {
            // Console-only is a legitimate local setup, but an *unnamed* one is also exactly what a
            // misconfigured deployment looks like. An explicit LOGGER=console is a decision and
            // says nothing; this is the case where nobody chose.
            logger.warn("Neither LOGGER nor LOKI_URL is set; logs stay on the console and are not shipped.",
                    Map.of("app", appName));
        }
        return logger;
    }

    /**
     * Builds for an already-parsed mode and an explicit file, for callers that resolve those
     * themselves — tests, and any app that wants its log file somewhere other than the default.
     */
    public static ManagedLogger create(
            String appName,
            LoggerMode mode,
            String lokiUrl,
            String lokiTenantId,
            Path diskFile
    ) {
        return create(appName, mode, lokiUrl, lokiTenantId, diskFile, System.out, System.err);
    }

    /** As {@link #create}, with the console streams injectable so a test can read what was written. */
    public static ManagedLogger create(
            String appName,
            LoggerMode mode,
            String lokiUrl,
            String lokiTenantId,
            Path diskFile,
            PrintStream out,
            PrintStream err
    ) {
        return create(appName, mode, lokiUrl, lokiTenantId, diskFile, out, err, err);
    }

    /**
     * As {@link #create}, separating <em>where records go</em> from <em>where a sink that could not
     * be opened is reported</em>.
     *
     * <p>They are the same stream for every server, which is why the overload above passes
     * {@code err} for both. They are not the same for a program whose console can be switched off:
     * silencing records must not also silence "this log file could not be opened", or the operator
     * who asked for a quiet terminal gets a run that discards everything and says nothing. insta is
     * that caller.
     */
    public static ManagedLogger create(
            String appName,
            LoggerMode mode,
            String lokiUrl,
            String lokiTenantId,
            Path diskFile,
            PrintStream out,
            PrintStream err,
            PrintStream diagnostics
    ) {
        Objects.requireNonNull(appName, "appName");
        Objects.requireNonNull(mode, "mode");
        ConsoleLogger console = new ConsoleLogger(appName, out, err);
        // Only ever written to at ERROR, so both its streams are the diagnostics one.
        ConsoleLogger reporter = new ConsoleLogger(appName, diagnostics, diagnostics);
        requireLokiUrlFor(mode, lokiUrl);

        Logger disk = mode.usesDisk() ? openDiskSink(mode, diskFile, reporter) : null;

        List<Logger> sinks = new ArrayList<>();
        List<AutoCloseable> closeables = new ArrayList<>();
        sinks.add(console);
        if (mode.writesEveryRecordToDisk() && disk != null) {
            sinks.add(disk);
        }
        if (mode.usesLoki()) {
            // Only loki-with-fallback hands the file to Loki: in `both` the file already has every
            // record, so diverting would write the failed ones a second time.
            Logger fallback = mode.divertsUnshippedRecordsToDisk() ? disk : null;
            LokiLogger loki = new LokiLogger(appName, URI.create(lokiUrl.trim()), lokiTenantId, fallback);
            sinks.add(loki);
            closeables.add(loki);
        }

        // FailSafeLogger last, so a sink that breaks mid-run cannot turn a request into an HTTP 500.
        return new ManagedLogger(mode, new FailSafeLogger(new CompositeLogger(sinks)), closeables);
    }

    /**
     * A second Loki channel for heartbeat records, or empty when no Loki is configured.
     *
     * <p>Here rather than built in a {@code main} for the same reason
     * {@link #fromConfiguration} is: this module stays the one place that decides what a Loki sink
     * looks like, so a new caller cannot quietly get different batching or a different label scheme.
     *
     * <p><strong>Why a separate channel and not the app logger.</strong> A heartbeat is one line per
     * interval forever — thousands a day per process, every one of them saying nothing happened. On
     * the app stream that noise is charged to whatever reads it: {@code apps/log-analyzer} triages
     * {@code {stream_type="app"}} for errors, and a heartbeat is neither an error nor something a
     * human should scroll past to find one. On its own {@code stream_type} it costs that reader
     * nothing and {@code apps/liveness-analyzer} can select it directly.
     *
     * <p><strong>It is Loki-only, with no console sink.</strong> A heartbeat exists to be absent —
     * its whole purpose is for something else to notice it stopped — and a copy on stdout would put
     * the same thousands of lines in {@code docker logs} where nobody is watching for a gap.
     *
     * <p>No fallback file either, and that is the honest behaviour rather than an omission: if Loki
     * cannot be reached the heartbeat does not arrive, the dead-man's switch fires, and that is
     * correct — nobody can currently verify the process is doing its job. A file that recorded the
     * heartbeat locally would only make the switch fire while claiming the opposite on disk.
     *
     * @param streamType the {@code stream_type} label, e.g. {@code heartbeat}.
     */
    public static Optional<ManagedLogger> heartbeatChannel(
            String streamType, String appName, String lokiUrl, String lokiTenantId) {
        Objects.requireNonNull(streamType, "streamType");
        Objects.requireNonNull(appName, "appName");
        if (!isSet(lokiUrl)) {
            return Optional.empty();
        }
        LokiLogger loki = LokiLogger.onStream(
                streamType, appName, URI.create(lokiUrl.trim()), lokiTenantId);
        // FailSafe even here: a heartbeat that threw would take down the scheduled round that emits
        // it, which would stop the very work the heartbeat is evidence of.
        return Optional.of(new ManagedLogger(
                LoggerMode.LOKI, new FailSafeLogger(loki), List.of(loki)));
    }

    /**
     * What a process uses when {@code LOGGER} is unset: Loki if an endpoint is configured, console
     * otherwise. This is the behavior every server had before {@code LOGGER} existed, so leaving
     * the key unset changes nothing about an existing deployment.
     */
    public static LoggerMode defaultMode(String lokiUrl) {
        return isSet(lokiUrl) ? LoggerMode.LOKI : LoggerMode.CONSOLE;
    }

    /**
     * Where a file sink writes unless told otherwise: {@code <log dir>/<app>.jsonl}, with the
     * directory resolved by {@link LogFiles} — so on a server it lands beside Spring's own log
     * rather than in a second place nobody thinks to look.
     */
    public static Path defaultDiskFile(String appName) {
        return LogFiles.resolve(appName, DISK_EXTENSION);
    }

    private static void requireLokiUrlFor(LoggerMode mode, String lokiUrl) {
        if (mode.usesLoki() && !isSet(lokiUrl)) {
            throw new IllegalStateException(
                    "LOGGER=" + mode.configurationValue() + " needs a Loki endpoint, but LOKI_URL is not set. "
                            + "Set LOKI_URL, or use LOGGER=disk or LOGGER=console.");
        }
    }

    private static Logger openDiskSink(LoggerMode mode, Path diskFile, ConsoleLogger console) {
        Objects.requireNonNull(diskFile, "diskFile");
        try {
            return new JsonLogger(diskFile);
        } catch (Exception failure) {
            // Loud, not fatal: the records still reach the console, and refusing to start would
            // turn a permissions problem into an outage.
            console.error("Cannot open the log file; continuing without the disk sink.", Map.of(
                    "file", diskFile.toString(),
                    "logger", mode.configurationValue(),
                    "error", failure.toString()));
            return null;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}

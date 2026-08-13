package dev.orwell.logging;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Which durable sink a process ships its records to, chosen from one configuration value — the
 * {@code LOGGER} environment variable on every server in this repo.
 *
 * <p>The console is deliberately <em>not</em> part of the choice: {@link ConsoleLogger} is always
 * attached, because stdout is what {@code docker logs} reads and an operator debugging a container
 * should never have to change configuration to see anything at all. So a mode names what happens
 * <em>in addition</em> to the console, and {@link #CONSOLE} is the mode that adds nothing.
 *
 * <p>The values, and what each is for:
 *
 * <ul>
 *   <li>{@link #CONSOLE} — console only. Local development, and the implicit default when no Loki
 *       endpoint is configured.</li>
 *   <li>{@link #DISK} — a JSON-lines file. For deployments with a collector tailing files, or
 *       where logs must survive the process without a network dependency.</li>
 *   <li>{@link #LOKI} — pushed straight to Loki, dropping what it cannot ship.</li>
 *   <li>{@link #LOKI_WITH_FALLBACK} — Loki, with the disk file catching only what Loki could not
 *       take. The steady state costs no disk I/O; an outage costs a file instead of lost records.
 *       This is the mode to pick when losing records during a Loki outage is unacceptable.</li>
 *   <li>{@link #BOTH} — every record to Loki <em>and</em> to disk, unconditionally. Two complete
 *       copies, at the price of a disk write on every call.</li>
 * </ul>
 *
 * <p>{@code LOKI_WITH_FALLBACK} and {@code BOTH} differ in exactly that: the fallback writes only
 * the records Loki rejected or never received, so the file is an outage record rather than a
 * duplicate of everything.
 */
public enum LoggerMode {
    CONSOLE("console"),
    DISK("disk"),
    LOKI("loki"),
    LOKI_WITH_FALLBACK("loki-with-fallback"),
    BOTH("both");

    private final String configurationValue;

    LoggerMode(String configurationValue) {
        this.configurationValue = configurationValue;
    }

    /** The spelling used in configuration, e.g. {@code loki-with-fallback}. */
    public String configurationValue() {
        return configurationValue;
    }

    /**
     * Parses a configured value. Case and the choice of {@code -} or {@code _} as separator are
     * both forgiven; anything else fails, because a value nobody can parse must not quietly
     * become the default — that is how a deployment ends up shipping nothing while looking fine.
     */
    public static LoggerMode parse(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("A logger mode is required; expected one of: " + valuesForMessage());
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (LoggerMode mode : values()) {
            if (mode.configurationValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unknown logger mode: " + configured + "; expected one of: " + valuesForMessage());
    }

    /** Parses a value that may legitimately be unset, falling back to {@code defaultMode}. */
    public static LoggerMode parseOrDefault(String configured, LoggerMode defaultMode) {
        return configured == null || configured.isBlank() ? defaultMode : parse(configured);
    }

    /** True when this mode needs a Loki endpoint to mean anything. */
    public boolean usesLoki() {
        return this == LOKI || this == LOKI_WITH_FALLBACK || this == BOTH;
    }

    /** True when every record is written to the file, not only the ones Loki could not take. */
    public boolean writesEveryRecordToDisk() {
        return this == DISK || this == BOTH;
    }

    /** True when the file exists to catch what Loki could not ship. */
    public boolean divertsUnshippedRecordsToDisk() {
        return this == LOKI_WITH_FALLBACK;
    }

    /** True when this mode needs a writable log file, for either of the two reasons above. */
    public boolean usesDisk() {
        return writesEveryRecordToDisk() || divertsUnshippedRecordsToDisk();
    }

    @Override
    public String toString() {
        return configurationValue;
    }

    private static String valuesForMessage() {
        return Arrays.stream(values()).map(LoggerMode::configurationValue).collect(Collectors.joining(", "));
    }
}

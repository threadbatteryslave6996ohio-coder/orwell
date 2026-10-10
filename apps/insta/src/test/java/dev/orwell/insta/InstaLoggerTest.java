package dev.orwell.insta;

import dev.orwell.logging.LoggerMode;
import dev.orwell.logging.ManagedLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which sinks the environment selects, and what happens when one of them cannot be opened.
 *
 * <p>The point of this class is that production can be pointed somewhere else without a code
 * change, so the tests are about the wiring rather than about any sink's own formatting. Since the
 * tree itself is now {@link dev.orwell.logging.LoggerSetup}'s — covered by its own tests — what is
 * left to prove here is insta's translation: that {@code LOGGER} works, that the pre-{@code LOGGER}
 * configurations still select what they always did, and that stdout stays clean.
 */
class InstaLoggerTest {

    @Test
    void writesToTheConsoleByDefault() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = logger(console, true, "", "")) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        assertThat(text(console)).contains("Fetched an Instagram profile.").contains("nasa");
    }

    /** Results are stdout; anything else on stdout would corrupt a pipe into jq. */
    @Test
    void keepsEveryRecordOffStdout() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = logger(console, true, "", "")) {
            logger.error("Instagram lookup failed upstream.", Map.of("username", "nasa"));
            logger.info("Fetched Instagram connections.", Map.of("count", 3));
        }

        // The stream handed in stands in for stderr: everything must land there, nothing elsewhere.
        assertThat(text(console))
                .contains("Instagram lookup failed upstream.")
                .contains("Fetched Instagram connections.");
    }

    @Test
    void addsAFileSinkWhenOneIsConfigured(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("insta.log");
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = logger(console, true, "", logFile.toString())) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        List<String> lines = Files.readAllLines(logFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"message\":\"Fetched an Instagram profile.\"")
                .contains("nasa");
        // Both sinks get the record; a file does not replace the console.
        assertThat(text(console)).contains("Fetched an Instagram profile.");
    }

    /** The swap that matters in production: ship records somewhere and keep the terminal clean. */
    @Test
    void canDropTheConsoleAndKeepAnotherSink(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("insta.log");
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = logger(console, false, "", logFile.toString())) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        assertThat(text(console)).isEmpty();
        assertThat(Files.readAllLines(logFile)).hasSize(1);
    }

    /** A log file that cannot be opened is a logging problem, not a reason to skip the lookup. */
    @Test
    void keepsGoingWhenTheLogFileCannotBeOpened(@TempDir Path directory) throws Exception {
        // A missing directory would not do: JsonLogger creates parents. A plain file standing
        // where a directory has to go is something it genuinely cannot open.
        Path blocker = Files.createFile(directory.resolve("blocker"));
        Path unwritable = blocker.resolve("insta.log");
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = logger(console, true, "", unwritable.toString())) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        String output = text(console);
        assertThat(output).contains("Cannot open the log file");
        // The record itself still reached the console sink.
        assertThat(output).contains("Fetched an Instagram profile.");
    }

    /**
     * Switching the console off silences <em>records</em>, not the report that a sink could not be
     * opened. The two are different things and this is the case that proves it: a cron job with
     * {@code INSTA_LOG_CONSOLE=false} and an unwritable {@code LOGGING_FILE_NAME} would otherwise
     * discard every record and say nothing anywhere, leaving the operator believing the file sink
     * works.
     */
    @Test
    void reportsAnUnopenableLogFileEvenWithTheConsoleOff(@TempDir Path directory) throws Exception {
        Path blocker = Files.createFile(directory.resolve("blocker"));
        Path unwritable = blocker.resolve("insta.log");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        try (ManagedLogger logger = InstaLogger.create(
                stream(console), stream(diagnostics), false, "", "", "", unwritable.toString())) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        assertThat(text(diagnostics)).contains("Cannot open the log file");
        // The records themselves still stay off the terminal — that is what the switch asks for.
        assertThat(text(console)).isEmpty();
        assertThat(text(diagnostics)).doesNotContain("Fetched an Instagram profile.");
    }

    /**
     * Records go nowhere when nothing is configured to take them, and that has to be survivable —
     * a caller logs unguarded and must not care whether a sink exists.
     */
    @Test
    void survivesHavingNoSinksAtAll() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = logger(console, false, "", "")) {
            assertThatCode(() -> logger.info("Fetched an Instagram profile.")).doesNotThrowAnyException();
        }

        assertThat(text(console)).isEmpty();
    }

    /**
     * The Loki sink batches on a daemon thread, so an unreachable Loki must not stall or fail the
     * program — and closing it must return rather than hang.
     */
    @Test
    void toleratesAnUnreachableLokiAndStillClosesPromptly() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        // Port 1 is reserved and refuses connections, standing in for Loki being down.
        ManagedLogger logger = logger(console, true, "http://127.0.0.1:1/loki/api/v1/push", "");

        assertThatCode(() -> {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
            logger.close();
        }).doesNotThrowAnyException();

        assertThat(text(console)).contains("Fetched an Instagram profile.");
    }

    @Test
    void ignoresABlankSinkSettingRatherThanTreatingItAsAValue() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        assertThatCode(() -> logger(console, true, "   ", "   ").close()).doesNotThrowAnyException();

        assertThat(text(console)).doesNotContain("Cannot open");
    }

    /**
     * The configurations that predate {@code LOGGER} have to keep selecting what they always did,
     * which is why insta derives its default from the keys rather than using
     * {@link dev.orwell.logging.LoggerSetup#defaultMode(String)}.
     */
    @Test
    void derivesTheModeFromWhicheverSinkKeysAreSet() {
        assertThat(InstaLogger.defaultMode("", "")).isEqualTo(LoggerMode.CONSOLE);
        assertThat(InstaLogger.defaultMode("", "/tmp/insta.jsonl")).isEqualTo(LoggerMode.DISK);
        assertThat(InstaLogger.defaultMode("http://loki:3100", "")).isEqualTo(LoggerMode.LOKI);
        assertThat(InstaLogger.defaultMode("http://loki:3100", "/tmp/insta.jsonl"))
                .isEqualTo(LoggerMode.BOTH);
    }

    /**
     * The mode insta could not reach before: Loki, with the file catching only what Loki refused.
     * With an unreachable Loki every record ends up in the file, which is the whole point of it.
     *
     * <p>The wait is not decoration. The diversion happens on the sink's worker thread, after its
     * push has failed, and {@code close()} interrupts that thread — so closing immediately would
     * race the very write being asserted on.
     */
    @Test
    void lokiWithFallbackDivertsUnshippedRecordsToTheFile(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("insta.jsonl");
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = InstaLogger.create(
                stream(console), stream(console), true, "loki-with-fallback",
                // Port 1 is reserved and refuses connections, standing in for Loki being down.
                "http://127.0.0.1:1/loki/api/v1/push", "", logFile.toString())) {
            assertThat(logger.mode()).isEqualTo(LoggerMode.LOKI_WITH_FALLBACK);
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
            awaitLine(logFile, "Fetched an Instagram profile.");
        }

        assertThat(Files.readAllLines(logFile))
                .anySatisfy(line -> assertThat(line).contains("Fetched an Instagram profile."));
    }

    private static void awaitLine(Path file, String text) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(file) && Files.readAllLines(file).stream().anyMatch(l -> l.contains(text))) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("The fallback sink never wrote " + text + " to " + file);
    }

    /** An explicit mode overrides the derivation, so a file can be configured and left unused. */
    @Test
    void anExplicitModeWinsOverTheDerivedOne(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("insta.jsonl");
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (ManagedLogger logger = InstaLogger.create(
                stream(console), stream(console), true, "console", "", "", logFile.toString())) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        assertThat(text(console)).contains("Fetched an Instagram profile.");
        assertThat(Files.exists(logFile)).isFalse();
    }

    /**
     * A value nobody can parse must not quietly become the default — that is how a run ends up
     * shipping nothing while looking fine. {@link InstaCli} turns this into exit code 2.
     */
    @Test
    void refusesAnUnknownMode() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        assertThatThrownBy(() -> InstaLogger.create(stream(console), stream(console), true, "lokki", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lokki");
    }

    /** Asking for Loki without an endpoint is a contradiction, not a sink that failed to open. */
    @Test
    void refusesALokiModeWithNoEndpoint() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        assertThatThrownBy(() -> InstaLogger.create(stream(console), stream(console), true, "loki", "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOKI_URL");
    }

    private static ManagedLogger logger(
            ByteArrayOutputStream console, boolean consoleEnabled, String lokiUrl, String logFile) {
        PrintStream stream = stream(console);
        return InstaLogger.create(stream, stream, consoleEnabled, "", lokiUrl, "", logFile);
    }

    private static PrintStream stream(ByteArrayOutputStream console) {
        return new PrintStream(console, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }
}

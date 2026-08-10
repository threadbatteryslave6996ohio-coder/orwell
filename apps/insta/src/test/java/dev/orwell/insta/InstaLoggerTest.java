package dev.orwell.insta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Which sinks the environment selects, and what happens when one of them cannot be opened.
 *
 * <p>The point of this class is that production can be pointed somewhere else without a code
 * change, so the tests are about the wiring rather than about any sink's own formatting.
 */
class InstaLoggerTest {

    @Test
    void writesToTheConsoleByDefault() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (InstaLogger logger = logger(console, true, "", "")) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        assertThat(text(console)).contains("Fetched an Instagram profile.").contains("nasa");
    }

    /** Results are stdout; anything else on stdout would corrupt a pipe into jq. */
    @Test
    void keepsEveryRecordOffStdout() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (InstaLogger logger = logger(console, true, "", "")) {
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

        try (InstaLogger logger = logger(console, true, "", logFile.toString())) {
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

        try (InstaLogger logger = logger(console, false, "", logFile.toString())) {
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

        try (InstaLogger logger = logger(console, true, "", unwritable.toString())) {
            logger.info("Fetched an Instagram profile.", Map.of("username", "nasa"));
        }

        String output = text(console);
        assertThat(output).contains("Could not open the log file");
        // The record itself still reached the console sink.
        assertThat(output).contains("Fetched an Instagram profile.");
    }

    /**
     * Records go nowhere when nothing is configured to take them, and that has to be survivable —
     * a caller logs unguarded and must not care whether a sink exists.
     */
    @Test
    void survivesHavingNoSinksAtAll() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        try (InstaLogger logger = logger(console, false, "", "")) {
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
        InstaLogger logger = logger(console, true, "http://127.0.0.1:1/loki/api/v1/push", "");

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

        assertThat(text(console)).doesNotContain("Could not");
    }

    private static InstaLogger logger(
            ByteArrayOutputStream console, boolean consoleEnabled, String lokiUrl, String logFile) {
        return InstaLogger.create(
                new PrintStream(console, true, StandardCharsets.UTF_8),
                consoleEnabled, lokiUrl, "", logFile);
    }

    private static String text(ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8);
    }
}

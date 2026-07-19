package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The below-minimum path in {@link PollInterval#resolve} ends in {@code System.exit}, so it
 * cannot be driven from a test JVM. Its two observable effects — the message text and the
 * stderr/logger reporting — are covered directly instead.
 */
class PollIntervalTest {
    private static final String LOG_DIRECTORY_PROPERTY = "custom.logger.dir";

    private static Env envWith(Map<String, String> overrides) {
        Map<String, String> source = new LinkedHashMap<>(overrides);
        source.putIfAbsent("REMOTE_SERVER_URL", "http://localhost:8080");
        return ClientEnvs.from(source);
    }

    /** Runs {@code action} with stderr captured, returning what it wrote. */
    private static String stderrOf(Runnable action) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void resolvesToOneSecondWhenIntervalIsUnset() {
        assertEquals(1000L, PollInterval.resolve(envWith(Map.of())));
    }

    @Test
    void resolvesConfiguredInterval() {
        assertEquals(250L, PollInterval.resolve(
                envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "250"))));
    }

    @Test
    void acceptsMinimumValue() {
        assertEquals(100L, PollInterval.resolve(
                envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "100"))));
    }

    @Test
    void staysSilentForAcceptedInterval() {
        Env env = envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "250"));

        assertEquals("", stderrOf(() -> PollInterval.resolve(env)));
    }

    @Test
    void rejectionMessageNamesTheVariableAndTheMinimum() {
        assertEquals("CLIPBOARD_POLL_INTERVAL_MS=50 is below the 100 ms minimum; refusing to start.",
                PollInterval.rejectionMessage(50L));
    }

    @Test
    void rejectionMessageCoversNonPositiveIntervals() {
        assertTrue(PollInterval.rejectionMessage(0L).startsWith("CLIPBOARD_POLL_INTERVAL_MS=0 is below"));
        assertTrue(PollInterval.rejectionMessage(-5L).startsWith("CLIPBOARD_POLL_INTERVAL_MS=-5 is below"));
    }

    @Test
    void reportWritesToStderrAndTheLoggerService(@TempDir Path logDirectory) throws Exception {
        String message = PollInterval.rejectionMessage(50L);
        String previousDirectory = System.getProperty(LOG_DIRECTORY_PROPERTY);
        System.setProperty(LOG_DIRECTORY_PROPERTY, logDirectory.toString());
        try {
            assertTrue(stderrOf(() -> PollInterval.report(message)).contains(message),
                    "expected the rejection on stderr");

            Path logFile = logDirectory.resolve("klippy-client.txt");
            assertTrue(Files.exists(logFile), "expected the logger service to create " + logFile);
            String logged = Files.readString(logFile, StandardCharsets.UTF_8);
            assertTrue(logged.contains(message), "expected the rejection in the log, got: " + logged);
            assertTrue(logged.contains("[ERROR]"), "expected the entry logged at ERROR, got: " + logged);
        } finally {
            if (previousDirectory == null) {
                System.clearProperty(LOG_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(LOG_DIRECTORY_PROPERTY, previousDirectory);
            }
        }
    }

    @Test
    void reportStillPrintsToStderrWhenLoggingFails(@TempDir Path logDirectory) throws Exception {
        String message = PollInterval.rejectionMessage(50L);
        Path unwritable = logDirectory.resolve("klippy-client.txt");
        // A directory where the log file belongs makes the logger service throw.
        Files.createDirectories(unwritable);

        String previousDirectory = System.getProperty(LOG_DIRECTORY_PROPERTY);
        System.setProperty(LOG_DIRECTORY_PROPERTY, logDirectory.toString());
        try {
            String stderr = stderrOf(() -> PollInterval.report(message));

            assertTrue(stderr.contains(message), "the exit reason must survive a logging failure");
            assertTrue(stderr.contains("Could not write the log entry"), "expected a logging-failure note");
        } finally {
            if (previousDirectory == null) {
                System.clearProperty(LOG_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(LOG_DIRECTORY_PROPERTY, previousDirectory);
            }
        }
    }
}

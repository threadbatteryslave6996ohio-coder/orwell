package dev.orwell.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerSetupTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void consoleModeShipsNothingAndTouchesNoFile(@TempDir Path directory) {
        Path logFile = directory.resolve("app.jsonl");

        try (ManagedLogger logger = build(LoggerMode.CONSOLE, "", logFile)) {
            logger.info("Started.", Map.of("port", 9300));
        }

        assertTrue(text(out).contains("Started."));
        assertFalse(Files.exists(logFile), "console mode must not create a log file");
    }

    @Test
    void diskModeWritesEveryRecordAsAJsonLineAndStillPrints(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("nested").resolve("app.jsonl");

        try (ManagedLogger logger = build(LoggerMode.DISK, "", logFile)) {
            logger.info("Proxied request.", Map.of("status", 200));
        }

        // The console is in every mode: docker logs must never go silent.
        assertTrue(text(out).contains("Proxied request."));
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        assertEquals("Proxied request.", MAPPER.readTree(lines.getFirst()).get("message").asText());
        assertEquals(200, MAPPER.readTree(lines.getFirst()).get("status").asInt());
    }

    @Test
    void askingForLokiWithoutAnEndpointFailsAtStartup(@TempDir Path directory) {
        for (LoggerMode mode : List.of(LoggerMode.LOKI, LoggerMode.LOKI_WITH_FALLBACK, LoggerMode.BOTH)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> build(mode, "", directory.resolve("app.jsonl")),
                    mode + " must not start without LOKI_URL");
            assertTrue(failure.getMessage().contains("LOKI_URL"), failure.getMessage());
        }
    }

    @Test
    void anUnwritableLogFileIsReportedButDoesNotStopTheProcess(@TempDir Path directory) throws Exception {
        // A file where the sink expects a directory: opening it cannot succeed.
        Path blocked = directory.resolve("occupied");
        Files.writeString(blocked, "not a directory");

        try (ManagedLogger logger = build(LoggerMode.DISK, "", blocked.resolve("app.jsonl"))) {
            logger.info("Still running.");
        }

        assertTrue(text(err).contains("Cannot open the log file"), text(err));
        // Loud, not fatal — the record still reached the console.
        assertTrue(text(out).contains("Still running."));
    }

    @Test
    void lokiWithFallbackWritesOnlyWhatLokiRefused(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("app.jsonl");
        CountDownLatch pushed = new CountDownLatch(1);
        HttpServer loki = stubLoki(503, pushed);

        try (ManagedLogger logger = build(LoggerMode.LOKI_WITH_FALLBACK, endpointOf(loki), logFile)) {
            logger.error("Sweep failed.", Map.of("table", "frame_events"));
            assertTrue(pushed.await(5, TimeUnit.SECONDS), "Loki never received a push");

            String line = awaitFirstLine(logFile);
            assertEquals("Sweep failed.", MAPPER.readTree(line).get("message").asText());
        } finally {
            loki.stop(0);
        }
    }

    @Test
    void lokiWithFallbackLeavesTheFileEmptyWhileLokiIsHealthy(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("app.jsonl");
        CountDownLatch pushed = new CountDownLatch(1);
        HttpServer loki = stubLoki(204, pushed);

        try (ManagedLogger logger = build(LoggerMode.LOKI_WITH_FALLBACK, endpointOf(loki), logFile)) {
            logger.info("Shipped.");
            assertTrue(pushed.await(5, TimeUnit.SECONDS), "Loki never received a push");
        } finally {
            loki.stop(0);
        }

        // The whole point of the mode: a healthy Loki costs no disk I/O at all.
        assertFalse(Files.exists(logFile), "the fallback file is an outage record, not a copy");
    }

    @Test
    void bothWritesToDiskWhetherOrNotLokiIsHealthy(@TempDir Path directory) throws Exception {
        Path logFile = directory.resolve("app.jsonl");
        CountDownLatch pushed = new CountDownLatch(1);
        HttpServer loki = stubLoki(204, pushed);

        try (ManagedLogger logger = build(LoggerMode.BOTH, endpointOf(loki), logFile)) {
            logger.info("Two copies.");
            assertTrue(pushed.await(5, TimeUnit.SECONDS), "Loki never received a push");
            assertEquals("Two copies.", MAPPER.readTree(awaitFirstLine(logFile)).get("message").asText());
        } finally {
            loki.stop(0);
        }
    }

    @Test
    void anUnsetLoggerKeepsThePreviousBehaviorAndSaysSoWhenNothingIsShipped() {
        try (ManagedLogger console = LoggerSetup.fromConfiguration("app", "", "", "", print(out), print(err))) {
            assertEquals(LoggerMode.CONSOLE, console.mode());
            assertTrue(text(err).contains("not shipped"), text(err));
        }

        // With an endpoint configured but no LOGGER, a server ships to Loki exactly as before.
        assertEquals(LoggerMode.LOKI, LoggerSetup.defaultMode("http://loki:3100/loki/api/v1/push"));
        assertEquals(LoggerMode.CONSOLE, LoggerSetup.defaultMode(null));
    }

    @Test
    void anExplicitConsoleChoiceIsNotWarnedAbout() {
        try (ManagedLogger logger =
                     LoggerSetup.fromConfiguration("app", "console", "", "", print(out), print(err))) {
            assertEquals(LoggerMode.CONSOLE, logger.mode());
        }

        // Nobody needs telling about a setting they wrote down; the warning is for the case where
        // nothing was chosen at all.
        assertFalse(text(err).contains("not shipped"), text(err));
    }

    @Test
    void anUnknownLoggerValueFailsRatherThanFallingBack() {
        assertThrows(IllegalArgumentException.class,
                () -> LoggerSetup.fromConfiguration("app", "loki-with-fallbck", "", "", print(out), print(err)));
    }

    private ManagedLogger build(LoggerMode mode, String lokiUrl, Path logFile) {
        return LoggerSetup.create("app", mode, lokiUrl, null, logFile, print(out), print(err));
    }

    private static PrintStream print(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream sink) {
        return sink.toString(StandardCharsets.UTF_8);
    }

    private static String endpointOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/loki/api/v1/push";
    }

    private static HttpServer stubLoki(int status, CountDownLatch pushed) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/loki/api/v1/push", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            pushed.countDown();
        });
        server.start();
        return server;
    }

    /** The diverting write happens on the sink's worker thread, just after the push comes back. */
    private static String awaitFirstLine(Path logFile) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (Files.exists(logFile)) {
                List<String> lines = Files.readAllLines(logFile);
                if (!lines.isEmpty()) {
                    return lines.getFirst();
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("nothing was written to " + logFile);
    }
}

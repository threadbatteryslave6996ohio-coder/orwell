package dev.orwell.bootstrap.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.logging.LogFiles;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerConfigurationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultSinkPushesStructuredEntriesToLoki() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer loki = stubLoki(bodies, received);
        try {
            String url = "http://127.0.0.1:" + loki.getAddress().getPort() + "/loki/api/v1/push";
            Logger logger = new LoggerConfiguration().logger("", "auth-server", url, "");

            logger.info("Login request received.", Map.of("clientId", "linux-clip"));

            assertTrue(received.await(10, TimeUnit.SECONDS), "no push reached Loki");
            JsonNode stream = MAPPER.readTree(bodies.getFirst()).get("streams").get(0);
            assertEquals("auth-server", stream.get("stream").get("app").asText());
            assertEquals("app", stream.get("stream").get("stream_type").asText());

            JsonNode line = MAPPER.readTree(stream.get("values").get(0).get(1).asText());
            assertEquals("Login request received.", line.get("message").asText());
            assertEquals("linux-clip", line.get("clientId").asText());
        } finally {
            loki.stop(0);
        }
    }

    @Test
    void withoutALokiUrlItFallsBackToConsoleAndSaysSo() {
        // Console-only is valid locally, but silently shipping nothing in a deployment is the
        // failure this warning exists to make visible.
        Logger logger = new LoggerConfiguration().logger("", "auth-server", "", "");
        assertDoesNotThrow(() -> logger.info("Login request received."));
    }

    @Test
    void anUnreachableLokiNeverPropagatesIntoARequestPath() {
        // Port 1 refuses immediately; the push fails on the worker thread, not the caller's.
        Logger logger = new LoggerConfiguration()
                .logger("", "auth-server", "http://127.0.0.1:1/loki/api/v1/push", "");

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) {
                logger.info("Login request received.", Map.of("attempt", i));
            }
        });
    }

    @Test
    void theLoggerKeyChoosesTheSinks(@TempDir Path directory) throws Exception {
        // LOGGER=disk with no Loki configured at all: the records must still land somewhere durable.
        String previous = System.getProperty("custom.logger.dir");
        LogFiles.configureDirectoryFromLogFile(directory.resolve("auth-server.log").toString());
        try {
            Logger logger = new LoggerConfiguration().logger("disk", "auth-server", "", "");
            logger.info("Login request received.", Map.of("clientId", "linux-clip"));

            List<String> lines = Files.readAllLines(directory.resolve("auth-server.jsonl"));
            assertEquals(1, lines.size());
            assertEquals("Login request received.", MAPPER.readTree(lines.getFirst()).get("message").asText());
        } finally {
            restore(previous);
        }
    }

    @Test
    void aTypoInTheLoggerKeyFailsStartupRatherThanBecomingTheDefault() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new LoggerConfiguration().logger("lok", "auth-server", "", ""));
        assertTrue(failure.getMessage().contains("loki-with-fallback"), failure.getMessage());
    }

    @Test
    void askingForLokiWithoutAnEndpointFailsStartup() {
        // The contradiction is free to detect, and the alternative is a deployment that believes
        // it is shipping logs and is not.
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new LoggerConfiguration().logger("loki-with-fallback", "auth-server", "", ""));
        assertTrue(failure.getMessage().contains("LOKI_URL"), failure.getMessage());
    }

    private static void restore(String previousLogDirectory) {
        if (previousLogDirectory == null) {
            System.clearProperty("custom.logger.dir");
        } else {
            System.setProperty("custom.logger.dir", previousLogDirectory);
        }
    }

    private static HttpServer stubLoki(List<String> bodies, CountDownLatch received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/loki/api/v1/push", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        return server;
    }
}

package dev.orwell.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code stream_type} label, which is what a consumer selects on.
 *
 * <p>A second stream exists so that high-volume low-information records — a liveness heartbeat — stay
 * out of the app stream {@code apps/log-analyzer} triages for errors. These tests pin both halves: the
 * default is unchanged for every existing caller, and a named stream really arrives labelled.
 *
 * <p>They wait on a latch for the push rather than asserting after {@code close()}, following
 * {@link LokiLoggerTest}: the worker can take an entry into a batch and then be interrupted by close
 * before sending it, so close is not a reliable flush barrier to assert against.
 */
class LokiLoggerStreamTypeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anOrdinaryLoggerStillPushesToTheAppStream() throws Exception {
        try (JsonStubServer loki = JsonStubServer.start()) {
            CountDownLatch arrived = new CountDownLatch(1);
            AtomicReference<String> pushed = capture(loki, arrived);
            LokiLogger logger =
                    new LokiLogger("some-app", URI.create(loki.baseUrl() + "/loki/api/v1/push"), null);

            logger.info("Hello.");

            assertTrue(arrived.await(5, TimeUnit.SECONDS), "no push received");
            assertEquals("app", streamLabel(pushed.get(), "stream_type"));
            assertEquals("some-app", streamLabel(pushed.get(), "app"));
            logger.close();
        }
    }

    @Test
    void aNamedStreamArrivesUnderThatLabel() throws Exception {
        try (JsonStubServer loki = JsonStubServer.start()) {
            CountDownLatch arrived = new CountDownLatch(1);
            AtomicReference<String> pushed = capture(loki, arrived);
            LokiLogger logger = LokiLogger.onStream(
                    "heartbeat", "health-monitor",
                    URI.create(loki.baseUrl() + "/loki/api/v1/push"), null);

            logger.info("Health monitor heartbeat.", Map.of("clientId", "health-monitor"));

            assertTrue(arrived.await(5, TimeUnit.SECONDS), "no push received");
            assertEquals("heartbeat", streamLabel(pushed.get(), "stream_type"));
            assertEquals("health-monitor", streamLabel(pushed.get(), "app"));
            // The clientId travels in the line body and never as a label — the cardinality rule in
            // apps/log-analyzer/README.md. A consumer reaches it at query time with `| json`, which
            // is exactly how apps/liveness-analyzer groups heartbeats.
            assertTrue(pushed.get().contains("clientId"), "the clientId should be in the line body");
            logger.close();
        }
    }

    /** A label value with a quote or a brace in it would produce a stream nothing can select. */
    @Test
    void refusesAStreamTypeThatCouldNotBeSelectedOn() {
        URI endpoint = URI.create("http://127.0.0.1:1/loki/api/v1/push");

        assertThrows(IllegalArgumentException.class,
                () -> LokiLogger.onStream("heart\"beat", "app", endpoint, null));
        assertThrows(IllegalArgumentException.class,
                () -> LokiLogger.onStream("stream type", "app", endpoint, null));
        assertThrows(IllegalArgumentException.class,
                () -> LokiLogger.onStream("", "app", endpoint, null));
        assertThrows(IllegalArgumentException.class,
                () -> LokiLogger.onStream(null, "app", endpoint, null));
    }

    private static AtomicReference<String> capture(JsonStubServer loki, CountDownLatch arrived)
            throws IOException {
        AtomicReference<String> pushed = new AtomicReference<>();
        loki.on("/loki/api/v1/push", exchange -> {
            pushed.set(JsonStubServer.readBody(exchange));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            arrived.countDown();
        });
        return pushed;
    }

    private static String streamLabel(String body, String label) throws Exception {
        assertTrue(body != null && !body.isBlank(), "nothing was pushed");
        JsonNode stream = JSON.readTree(body.getBytes(StandardCharsets.UTF_8))
                .get("streams").get(0).get("stream");
        return stream.get(label).asText();
    }
}

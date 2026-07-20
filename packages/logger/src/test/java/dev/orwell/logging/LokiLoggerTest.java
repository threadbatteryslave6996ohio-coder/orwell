package dev.orwell.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LokiLoggerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shipsEntriesToLokiAsAPushPayload() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = stubLoki(200, bodies, received);
        try (LokiLogger logger = logger(server, 10_000, 500, Duration.ofMillis(50))) {
            logger.info("Login request received.", Map.of("clientId", "linux-clip"));

            assertTrue(received.await(5, TimeUnit.SECONDS), "no push received");
            JsonNode stream = MAPPER.readTree(bodies.getFirst()).get("streams").get(0);

            assertEquals("auth-server", stream.get("stream").get("app").asText());
            assertEquals("app", stream.get("stream").get("stream_type").asText());
            assertEquals("INFO", stream.get("stream").get("level").asText());

            JsonNode value = stream.get("values").get(0);
            assertTrue(value.get(0).asText().matches("\\d{19}"), "epoch nanoseconds: " + value.get(0).asText());
            JsonNode line = MAPPER.readTree(value.get(1).asText());
            assertEquals("Login request received.", line.get("message").asText());
            assertEquals("linux-clip", line.get("clientId").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void groupsABatchIntoOneStreamPerLabelSet() throws Exception {
        // The worker unblocks the moment the first entry is queued, so logging three entries
        // back to back does not guarantee they share a batch — it ships whatever has arrived.
        // Pin the worker inside a warm-up request while the three entries queue behind it, so
        // the batch under test is the whole set rather than however many won the race.
        List<String> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch warmUpArrived = new CountDownLatch(1);
        CountDownLatch releaseWarmUp = new CountDownLatch(1);
        CountDownLatch batchArrived = new CountDownLatch(1);
        HttpServer server = gatedLoki(bodies, warmUpArrived, releaseWarmUp, batchArrived);
        try (LokiLogger logger = logger(server, 10_000, 500, Duration.ofMillis(100))) {
            logger.info("warm-up");
            assertTrue(warmUpArrived.await(5, TimeUnit.SECONDS), "worker never sent the warm-up entry");

            logger.info("one");
            logger.info("two");
            logger.error("three");
            releaseWarmUp.countDown();

            assertTrue(batchArrived.await(5, TimeUnit.SECONDS), "no batch after the warm-up was released");
            JsonNode streams = MAPPER.readTree(bodies.get(1)).get("streams");

            // level is a label, so INFO and ERROR are distinct streams; same-level entries share one.
            int total = 0;
            for (JsonNode stream : streams) {
                total += stream.get("values").size();
            }
            assertEquals(3, total);
            assertTrue(streams.size() <= 2, "expected at most one stream per level, got " + streams.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dropsRatherThanBlockingWhenTheQueueIsFull() throws Exception {
        // A Loki that never answers: the worker blocks on its request, so the queue fills.
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/loki/api/v1/push", exchange -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try (LokiLogger logger = logger(server, 4, 1, Duration.ofMillis(10))) {
            long start = System.nanoTime();
            for (int i = 0; i < 5_000; i++) {
                logger.info("entry " + i);
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            // The point of the sink: a stalled Loki must never slow a request path.
            assertTrue(elapsedMillis < 2_000, "logging blocked for " + elapsedMillis + "ms");
            assertTrue(logger.droppedEntries() > 0, "expected drops once the bounded queue filled");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aRejectingLokiNeverFailsTheCaller() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = stubLoki(429, bodies, received);
        try (LokiLogger logger = logger(server, 10_000, 500, Duration.ofMillis(50))) {
            logger.info("Login request received.");
            assertTrue(received.await(5, TimeUnit.SECONDS));
            // Rejected batches are counted as dropped, and the caller saw nothing.
            logger.info("still accepting calls");
        } finally {
            server.stop(0);
        }
    }

    private static LokiLogger logger(HttpServer server, int capacity, int batch, Duration flush) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/loki/api/v1/push");
        return new LokiLogger("auth-server", endpoint, null, capacity, batch, flush,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    /**
     * A Loki that holds the worker inside its first request until released, so a test can queue
     * entries knowing the worker cannot pick them up yet. The worker is the only client and it is
     * single-threaded, so "first request" is unambiguous and blocking the handler is safe.
     */
    private static HttpServer gatedLoki(
            List<String> bodies,
            CountDownLatch warmUpArrived,
            CountDownLatch releaseWarmUp,
            CountDownLatch batchArrived
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/loki/api/v1/push", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean warmUp = bodies.isEmpty();
            bodies.add(body);
            if (warmUp) {
                warmUpArrived.countDown();
                try {
                    releaseWarmUp.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            if (!warmUp) {
                batchArrived.countDown();
            }
        });
        server.start();
        return server;
    }

    private static HttpServer stubLoki(int status, List<String> bodies, CountDownLatch received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/loki/api/v1/push", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        return server;
    }
}

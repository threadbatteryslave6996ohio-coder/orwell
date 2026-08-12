package dev.orwell.bucket.frame.client;

import com.sun.net.httpserver.HttpServer;
import dev.orwell.primitives.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client against a real HTTP server speaking real SSE, because everything worth testing here
 * is about the wire: how events are framed, what happens when a stream drops mid-flight, and
 * whether the resume header asks for the right thing.
 */
class FrameStreamClientTest {
    private HttpServer server;
    private FrameStreamClient client;

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a server that writes whatever the handler produces to the SSE body. */
    private String serve(SseScript script) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/frames/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                script.write(exchange.getRequestHeaders().getFirst("Last-Event-ID"), out);
            } catch (Exception exception) {
                // A dropped connection is the point of some of these tests.
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(OutputStream out, long id, String source, byte[] frame) throws Exception {
        String payload = "{\"frameId\":" + id + ",\"source\":\"" + source + "\","
                + "\"frameIndex\":" + id + ",\"capturedAt\":\"2026-08-11T12:00:00Z\","
                + "\"sha256\":\"" + Sha256.hex(frame) + "\","
                + "\"frameBase64\":\"" + Base64.getEncoder().encodeToString(frame) + "\"}";
        out.write(("id: " + id + "\nevent: frame\ndata: " + payload + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void awaitTrue(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("condition never became true");
    }

    private FrameStreamClient watching(String baseUrl, FrameListener listener) {
        FrameStreamOptions options = new FrameStreamOptions(
                baseUrl, null, "test-watcher", null,
                Duration.ofMillis(50), Duration.ofMillis(200), true);
        FrameStreamClient started = new FrameStreamClient(options, listener, entry -> { });
        started.start();
        return started;
    }

    @Test
    void framesArriveDecodedAndInOrder() throws Exception {
        String baseUrl = serve((lastEventId, out) -> {
            for (long id = 1; id <= 3; id++) {
                send(out, id, "cam1", ("frame-" + id).getBytes(StandardCharsets.UTF_8));
            }
            Thread.sleep(5_000);
        });
        List<Frame> received = new CopyOnWriteArrayList<>();
        client = watching(baseUrl, received::add);

        awaitTrue(() -> received.size() == 3);

        assertEquals(List.of(1L, 2L, 3L), received.stream().map(Frame::frameId).toList());
        assertEquals("cam1", received.get(0).source());
        // Base64 undone for the caller: a listener gets bytes, never an encoded string.
        assertEquals("frame-1", new String(received.get(0).bytes(), StandardCharsets.UTF_8));
        assertEquals(3L, client.lastFrameId());
        assertTrue(client.connected());
        assertNull(client.lastError());
    }

    /**
     * The framing bug this class exists to not have: a payload split across several {@code data:}
     * lines is one JSON document, not several. Reading line-by-line works until a payload wraps.
     */
    @Test
    void aPayloadSplitAcrossDataLinesIsOneEvent() throws Exception {
        byte[] frame = "split".getBytes(StandardCharsets.UTF_8);
        String baseUrl = serve((lastEventId, out) -> {
            out.write(("id: 7\nevent: frame\n"
                    + "data: {\"frameId\":7,\"source\":\"cam1\",\n"
                    + "data: \"capturedAt\":\"2026-08-11T12:00:00Z\",\n"
                    + "data: \"sha256\":\"" + Sha256.hex(frame) + "\",\n"
                    + "data: \"frameBase64\":\"" + Base64.getEncoder().encodeToString(frame) + "\"}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(5_000);
        });
        List<Frame> received = new CopyOnWriteArrayList<>();
        client = watching(baseUrl, received::add);

        awaitTrue(() -> !received.isEmpty());

        assertEquals(7L, received.get(0).frameId());
        assertEquals("split", new String(received.get(0).bytes(), StandardCharsets.UTF_8));
    }

    /** A dropped stream reconnects and asks to resume after the last frame it delivered. */
    @Test
    void aDroppedStreamResumesAfterTheLastDeliveredFrame() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicReference<String> resumeHeader = new AtomicReference<>();
        String baseUrl = serve((lastEventId, out) -> {
            int attempt = connections.incrementAndGet();
            if (attempt == 1) {
                send(out, 1, "cam1", "one".getBytes(StandardCharsets.UTF_8));
                send(out, 2, "cam1", "two".getBytes(StandardCharsets.UTF_8));
                return;   // drop the stream
            }
            resumeHeader.set(lastEventId);
            send(out, 3, "cam1", "three".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(5_000);
        });
        List<Frame> received = new CopyOnWriteArrayList<>();
        client = watching(baseUrl, received::add);

        awaitTrue(() -> received.size() == 3);

        // Resume is keyed on the last frame *delivered*, so nothing falls in the gap.
        assertEquals("2", resumeHeader.get());
        assertEquals(List.of(1L, 2L, 3L), received.stream().map(Frame::frameId).toList());
        assertTrue(client.reconnectsTotal() >= 1);
    }

    /** A frame whose bytes do not match the advertised hash is skipped, not delivered. */
    @Test
    void aHashMismatchIsSkippedRatherThanDelivered() throws Exception {
        String baseUrl = serve((lastEventId, out) -> {
            String payload = "{\"frameId\":4,\"source\":\"cam1\","
                    + "\"capturedAt\":\"2026-08-11T12:00:00Z\",\"sha256\":\"" + "0".repeat(64) + "\","
                    + "\"frameBase64\":\"" + Base64.getEncoder().encodeToString("real".getBytes()) + "\"}";
            out.write(("id: 4\nevent: frame\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            send(out, 5, "cam1", "good".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(5_000);
        });
        List<Frame> received = new CopyOnWriteArrayList<>();
        client = watching(baseUrl, received::add);

        awaitTrue(() -> !received.isEmpty());

        // The corrupt one never reaches the listener, and the stream keeps moving past it.
        assertEquals(List.of(5L), received.stream().map(Frame::frameId).toList());
    }

    /** A listener that throws must not end the subscription. */
    @Test
    void aThrowingListenerDoesNotEndTheStream() throws Exception {
        String baseUrl = serve((lastEventId, out) -> {
            for (long id = 1; id <= 3; id++) {
                send(out, id, "cam1", ("frame-" + id).getBytes(StandardCharsets.UTF_8));
            }
            Thread.sleep(5_000);
        });
        List<Long> seen = new CopyOnWriteArrayList<>();
        client = watching(baseUrl, frame -> {
            seen.add(frame.frameId());
            if (frame.frameId() == 1) {
                throw new IllegalStateException("undecodable");
            }
        });

        awaitTrue(() -> seen.size() == 3);

        assertEquals(List.of(1L, 2L, 3L), seen);
        assertTrue(client.connected());
    }

    @Test
    void theStreamUriCarriesTheFiltersItWasGiven() {
        FrameStreamOptions options = FrameStreamOptions.of("http://hub:9001/", "watcher-1")
                .withSource("cam 1")
                .withFrom(0L);

        assertEquals("http://hub:9001/frames/stream?source=cam+1&subscription=watcher-1&from=0",
                options.streamUri());
    }

    @Test
    void healthDetailsReportWhatAnOperatorNeeds() throws Exception {
        String baseUrl = serve((lastEventId, out) -> {
            send(out, 9, "cam1", "x".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(5_000);
        });
        client = watching(baseUrl, frame -> { });

        awaitTrue(() -> client.lastFrameId() == 9L);

        var details = client.healthDetails();
        assertEquals(true, details.get("connected"));
        assertEquals(9L, details.get("lastFrameId"));
        assertEquals(1L, details.get("framesReceivedTotal"));
        assertNotNull(details);
    }

    @FunctionalInterface
    private interface SseScript {
        void write(String lastEventId, OutputStream out) throws Exception;
    }
}

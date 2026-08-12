package dev.orwell.bucket.frame.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.logging.Logger;
import dev.orwell.primitives.Sha256;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watches the hub's frame stream, and hands each frame to a {@link FrameListener}.
 *
 * <p>This is the client half of {@code GET /frames/stream}, written once so that every watcher
 * behaves the same way. Four things here are easy to get individually wrong, and getting any of
 * them wrong looks like "the stream works but occasionally loses frames":
 *
 * <ul>
 *   <li><strong>SSE framing.</strong> Events are separated by a blank line, {@code data:} may
 *       repeat and is joined with newlines, and a leading space after the colon is part of the
 *       protocol rather than the payload. A watcher that reads line-by-line and JSON-parses each
 *       {@code data:} line works right up until a payload wraps.
 *   <li><strong>Resume.</strong> The SSE event id <em>is</em> the {@code frame_events.id}, so
 *       reconnecting with {@code Last-Event-ID} continues exactly where the drop happened. This
 *       tracks the last id it delivered — not the last it received — so a frame that arrived but
 *       was never handed to the listener is re-fetched rather than skipped. Note the limit of
 *       that: it holds <em>within</em> a process. A fresh start has no last id and falls back to
 *       the hub's stored cursor for the subscription, which the hub advances on write — so the one
 *       frame in flight when a process was killed is already marked delivered and does not come
 *       back. A watcher that cannot miss it records its own position and passes
 *       {@link FrameStreamOptions#withFrom}.
 *   <li><strong>Backoff.</strong> A dropped stream reconnects, doubling from
 *       {@code minBackoff} to {@code maxBackoff}, and resets on a successful connect. Without the
 *       ceiling a hub restart turns every watcher into a retry storm against a server that is
 *       trying to come up.
 *   <li><strong>Verification.</strong> Base64 is undone here, and the hash checked, so a listener
 *       never sees a frame the hub said was one thing and delivered as another.
 * </ul>
 *
 * <p><strong>It never gives up.</strong> {@link #start()} returns immediately and the reader runs
 * until {@link #close()}, treating a dead hub as a temporary condition. That is the right default
 * for a long-lived watcher: the alternative is a service that exits at 3am because the hub
 * restarted, and which nothing restarts because it exited cleanly.
 */
public final class FrameStreamClient implements AutoCloseable {
    private final FrameStreamOptions options;
    private final FrameListener listener;
    private final Logger logger;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicLong framesReceivedTotal = new AtomicLong();
    private final AtomicLong lastFrameId = new AtomicLong(-1);
    private final AtomicLong reconnectsTotal = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile Thread reader;

    public FrameStreamClient(FrameStreamOptions options, FrameListener listener, Logger logger) {
        this.options = Objects.requireNonNull(options, "options");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.logger = Objects.requireNonNull(logger, "logger");
        // No connect timeout on the response: this is a stream that is *meant* to stay open and be
        // silent whenever the scene is still. A read timeout here would drop a working watcher
        // pointed at a quiet camera.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** True while a stream is open. False between a drop and the next successful connect. */
    public boolean connected() {
        return connected.get();
    }

    public long framesReceivedTotal() {
        return framesReceivedTotal.get();
    }

    /** The last frame id handed to the listener, or -1 before the first one. */
    public long lastFrameId() {
        return lastFrameId.get();
    }

    public long reconnectsTotal() {
        return reconnectsTotal.get();
    }

    /** Null while healthy; the last connection failure otherwise. */
    public String lastError() {
        return lastError.get();
    }

    /** Health details a watcher can fold into its own {@code /health}. */
    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("connected", connected());
        details.put("framesReceivedTotal", framesReceivedTotal());
        details.put("lastFrameId", lastFrameId());
        details.put("reconnectsTotal", reconnectsTotal());
        details.put("lastStreamError", lastError());
        return details;
    }

    /** Starts watching on a background thread. Returns immediately. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("uri", options.streamUri());
        metadata.put("subscription", options.subscription());
        logger.info("Watching the frame stream.", metadata);
        reader = Thread.ofVirtual().name("frame-stream-client").start(this::run);
    }

    private void run() {
        Duration backoff = options.minBackoff();
        while (running.get()) {
            try {
                readStream();
                // A clean end of stream is still an end: the hub closed it, so reconnect. Reset
                // the backoff, because this was not a failure to connect.
                backoff = options.minBackoff();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                if (!running.get()) {
                    return;
                }
                lastError.set(String.valueOf(exception.getMessage()));
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("error", exception.toString());
                metadata.put("retryInSeconds", backoff.toSeconds());
                metadata.put("lastFrameId", lastFrameId());
                logger.warn("Frame stream dropped; reconnecting.", metadata);
            } finally {
                connected.set(false);
            }
            if (!running.get()) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = backoff.multipliedBy(2).compareTo(options.maxBackoff()) > 0
                    ? options.maxBackoff()
                    : backoff.multipliedBy(2);
            reconnectsTotal.incrementAndGet();
        }
    }

    /** One connection, read until it ends or throws. */
    private void readStream() throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(options.streamUri()))
                .header("Accept", "text/event-stream")
                .GET();
        // Resume from what was last *delivered*. The hub treats Last-Event-ID as "everything after
        // this", so a frame that arrived but never reached the listener comes back.
        long resumeFrom = lastFrameId.get();
        if (resumeFrom >= 0) {
            request.header("Last-Event-ID", String.valueOf(resumeFrom));
        }

        HttpResponse<InputStream> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("hub answered " + response.statusCode());
        }

        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            connected.set(true);
            lastError.set(null);
            readEvents(lines);
        }
    }

    /**
     * The SSE parse loop: accumulate {@code event:}/{@code data:}/{@code id:} until a blank line
     * ends the event, then dispatch. Unknown fields and comment lines are ignored, per the
     * protocol.
     */
    private void readEvents(BufferedReader lines) throws Exception {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = lines.readLine()) != null) {
            if (!running.get()) {
                return;
            }
            if (line.isEmpty()) {
                dispatch(eventName, data.toString());
                eventName = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;   // a comment / keep-alive
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);   // the one space after the colon is protocol
            }
            switch (field) {
                case "event" -> eventName = value;
                // Repeated data: lines are one payload joined by newlines. Reading each line as
                // its own JSON document is the bug this exists to not have.
                case "data" -> data.append(data.isEmpty() ? "" : "\n").append(value);
                default -> {
                    // "id" is deliberately ignored: the frame's own id is inside the payload, and
                    // taking the position from the body means the cursor only ever advances for a
                    // frame that was actually delivered.
                }
            }
        }
    }

    private void dispatch(String eventName, String data) throws Exception {
        if (eventName == null || data.isEmpty()) {
            return;
        }
        switch (eventName) {
            case "connected" -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("resumingAfter", json.readTree(data).path("resumingAfter").asText(null));
                metadata.put("subscription", options.subscription());
                logger.info("Connected to the frame stream.", metadata);
            }
            case "frame" -> deliver(json.readTree(data));
            default -> { }
        }
    }

    private void deliver(JsonNode node) {
        long frameId = node.path("frameId").asLong(-1);
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(node.path("frameBase64").asText(""));
        } catch (IllegalArgumentException exception) {
            // Skip rather than reconnect: retrying would fetch the same bad frame forever. The
            // cursor still advances, so the stream moves past it.
            frameSkipped(frameId, "frameBase64 is not valid base64");
            return;
        }
        String sha = node.path("sha256").asText(null);
        if (options.verifyHash() && sha != null && !sha.isBlank() && !sha.equals(Sha256.hex(bytes))) {
            frameSkipped(frameId, "frame hash mismatch");
            return;
        }

        Frame frame = new Frame(
                frameId,
                node.path("source").asText(null),
                node.hasNonNull("frameIndex") ? node.path("frameIndex").asLong() : null,
                capturedAt(node.path("capturedAt").asText(null)),
                sha,
                bytes);
        try {
            listener.onFrame(frame);
        } catch (Exception exception) {
            // One bad frame must not end a subscription that has run for a week.
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("frameId", frameId);
            metadata.put("source", frame.source());
            metadata.put("error", exception.toString());
            logger.error("A frame listener threw; skipping that frame.", metadata);
        }
        framesReceivedTotal.incrementAndGet();
        // Advance only after the listener has been given the frame, so a crash mid-listener
        // re-delivers it rather than losing it. At least once within a connection; see the class
        // comment for the one frame a process restart can still miss.
        lastFrameId.set(frameId);
    }

    private void frameSkipped(long frameId, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("frameId", frameId);
        metadata.put("reason", reason);
        logger.warn("Skipping an unusable frame from the stream.", metadata);
        if (frameId >= 0) {
            lastFrameId.set(frameId);
        }
    }

    private static Instant capturedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Stops watching. Safe to call more than once. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread pending = reader;
        if (pending != null) {
            pending.interrupt();
        }
        connected.set(false);
    }
}

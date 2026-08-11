package dev.orwell.bucket.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bootstrap.web.SharedJson;
import dev.orwell.bucket.detection.entity.FrameCursorEntity;
import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.repository.FrameCursorRepository;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The relay: every frame pushed to the hub goes out to whoever is connected, and a client that was
 * away is replayed what it missed before it joins the live stream.
 *
 * <p>Each connection is served by one virtual thread running two phases. It first reads stored
 * frames forward from its start cursor and sends them; only when the store is exhausted does it
 * switch to the live queue. Live frames are queued from the moment the connection registers — not
 * from the moment catch-up finishes — so nothing falls into the gap between the two phases, and
 * the pump skips any queued frame whose id it already sent during catch-up so nothing is sent
 * twice. The SSE event id is the {@code frame_events.id}, which is what lets a browser's automatic
 * {@code Last-Event-ID} reconnect resume exactly where it stopped.
 *
 * <p><strong>A slow client must never slow the producers.</strong> Broadcast is called on the
 * ingest request thread and only ever enqueues. When a client cannot keep up its bounded queue
 * fills and the hub drops that client's <em>oldest</em> frame — on a live feed the freshest frame
 * is the useful one, and unbounded buffering would turn a slow client into an out-of-memory error.
 * A named subscription that is dropping frames will re-fetch them from the store on its next
 * reconnect; an unnamed one loses them. Drops are counted and reported on {@code /health}.
 */
@Component
public class FrameHub {
    /** Stored frames read per catch-up query. Small: each row carries a whole JPEG. */
    private static final int REPLAY_BATCH = 20;
    /** Minimum gap between cursor writes, so a 5 fps stream is not a 5 Hz write load. */
    private static final long CURSOR_WRITE_INTERVAL_MILLIS = 2000;

    private final ObjectMapper json = SharedJson.mapper();
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicLong nextConnectionId = new AtomicLong(1);
    private final AtomicLong framesDistributedTotal = new AtomicLong();
    private final AtomicLong framesReplayedTotal = new AtomicLong();
    private final AtomicLong framesDroppedTotal = new AtomicLong();
    private final FrameEventRepository events;
    private final FrameCursorRepository cursors;
    private final int queueDepth;
    private final Logger logger;

    public FrameHub(
            FrameEventRepository events,
            FrameCursorRepository cursors,
            @Value("${detection.stream.queue-depth}") int queueDepth,
            Logger logger) {
        this.events = Objects.requireNonNull(events, "events");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.queueDepth = queueDepth;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public int connectedCount() {
        return connections.size();
    }

    public long framesDistributedTotal() {
        return framesDistributedTotal.get();
    }

    public long framesReplayedTotal() {
        return framesReplayedTotal.get();
    }

    public long framesDroppedTotal() {
        return framesDroppedTotal.get();
    }

    /**
     * Opens a stream for one client.
     *
     * @param source       restricts the client to one camera; null receives every source.
     * @param subscription durable cursor name; null makes the connection ephemeral.
     * @param from         explicit start id — the client receives frames <em>after</em> this one.
     *                     Null falls back to the subscription's stored cursor, then to the head.
     */
    public SseEmitter connect(String source, String subscription, Long from) {
        // No timeout: a viewer holds the connection open indefinitely, and letting the container
        // expire it would look to the client like a server fault every few minutes.
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        long id = nextConnectionId.getAndIncrement();
        long start = startCursor(source, subscription, from);
        Connection connection = new Connection(id, source, subscription, emitter, start);
        // Registered before catch-up begins, so frames arriving during the replay are queued
        // rather than missed.
        connections.put(id, connection);

        emitter.onCompletion(() -> disconnect(id));
        emitter.onTimeout(() -> disconnect(id));
        emitter.onError(error -> disconnect(id));
        connection.start();

        logger.info("Client connected to the frame stream.", Map.of(
                "connectionId", id,
                "source", source == null ? "*" : source,
                "subscription", subscription == null ? "(ephemeral)" : subscription,
                "startCursor", start,
                "connected", connections.size()));
        return emitter;
    }

    /**
     * Where a new connection begins. An explicit {@code from} wins, then a stored cursor for the
     * subscription name, then the current head — so an unnamed client sees what happens next
     * rather than replaying the whole retention window.
     */
    private long startCursor(String source, String subscription, Long from) {
        if (from != null) {
            return Math.max(0L, from);
        }
        if (subscription != null) {
            return cursors.findByName(subscription)
                    .map(FrameCursorEntity::getLastDeliveredId)
                    .orElseGet(this::head);
        }
        return head();
    }

    private long head() {
        return events.findTopByOrderByIdDesc().map(FrameEventEntity::getId).orElse(0L);
    }

    /**
     * Hands a stored frame to every connected client that wants it.
     *
     * @return how many connections it was queued for, so a producer can tell nobody is watching.
     */
    public int broadcast(FrameEventEntity frame) {
        String payload;
        try {
            payload = json.writeValueAsString(message(frame));
        } catch (Exception exception) {
            logger.error("Could not serialize a frame for the stream.", Map.of(
                    "frameId", frame.getId(),
                    "error", String.valueOf(exception.getMessage())));
            return 0;
        }
        Event event = new Event("frame", frame.getId(), payload);
        int recipients = 0;
        for (Connection connection : connections.values()) {
            if (connection.wants(frame.getSource())) {
                connection.enqueue(event);
                recipients++;
            }
        }
        return recipients;
    }

    /** The wire form of a frame, shared by the live path and the replay path. */
    private static Map<String, Object> message(FrameEventEntity frame) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("frameId", frame.getId());
        message.put("source", frame.getSource());
        message.put("frameIndex", frame.getFrameIndex());
        message.put("capturedAt", frame.getCapturedAt().toString());
        message.put("sha256", frame.getSha256());
        message.put("changed", frame.isChanged());
        message.put("changedFraction", frame.getChangedFraction());
        message.put("frameBase64", Base64.getEncoder().encodeToString(frame.getFrameBytes()));
        return message;
    }

    private void disconnect(long id) {
        Connection connection = connections.remove(id);
        if (connection != null) {
            connection.stop();
            connection.persistCursor(true);
            logger.info("Client left the frame stream.", Map.of(
                    "connectionId", id,
                    "droppedFrames", connection.dropped.get(),
                    "lastSentId", connection.lastSentId,
                    "connected", connections.size()));
        }
    }

    /** A queued SSE event: name, the frame id it carries, and its serialized JSON payload. */
    private record Event(String name, long frameId, String payload) {
    }

    /** One connected client: a bounded queue plus the virtual thread that replays then streams. */
    private final class Connection {
        private final long id;
        private final String source;
        private final String subscription;
        private final SseEmitter emitter;
        private final BlockingQueue<Event> queue = new ArrayBlockingQueue<>(queueDepth);
        private final AtomicLong dropped = new AtomicLong();
        private volatile long lastSentId;
        private volatile long cursorWrittenAtMillis;
        private volatile Thread pump;
        private volatile boolean open = true;

        private Connection(long id, String source, String subscription, SseEmitter emitter,
                long startCursor) {
            this.id = id;
            this.source = source;
            this.subscription = subscription;
            this.emitter = emitter;
            this.lastSentId = startCursor;
        }

        private boolean wants(String frameSource) {
            return source == null || source.equals(frameSource);
        }

        private void enqueue(Event event) {
            if (queue.offer(event)) {
                return;
            }
            // Full: make room by discarding the oldest frame this client has not read yet.
            queue.poll();
            dropped.incrementAndGet();
            framesDroppedTotal.incrementAndGet();
            if (!queue.offer(event)) {
                // Another producer took the slot in between; drop this frame rather than spin.
                dropped.incrementAndGet();
                framesDroppedTotal.incrementAndGet();
            }
        }

        private void start() {
            pump = Thread.ofVirtual().name("frame-stream-" + id).start(this::run);
        }

        private void run() {
            try {
                sendControl();
                replay();
                stream();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                // Usually the client hanging up mid-send, which is not worth an ERROR line.
                // completeWithError triggers onError, which unregisters us.
                emitter.completeWithError(exception);
            }
        }

        /** Flushes headers so a viewer knows it is live, and tells it where the replay starts. */
        private void sendControl() throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("connected", true);
            body.put("source", source);
            body.put("subscription", subscription);
            body.put("resumingAfter", lastSentId);
            emitter.send(SseEmitter.event().name("connected").data(json.writeValueAsString(body)));
        }

        /**
         * Phase one: walk the stored frames forward from the start cursor. Ends when the store has
         * nothing newer, at which point the live queue takes over.
         */
        private void replay() throws Exception {
            while (open) {
                List<FrameEventEntity> batch = source == null
                        ? events.findByIdGreaterThanOrderByIdAsc(
                                lastSentId, PageRequest.of(0, REPLAY_BATCH))
                        : events.findByIdGreaterThanAndSourceOrderByIdAsc(
                                lastSentId, source, PageRequest.of(0, REPLAY_BATCH));
                if (batch.isEmpty()) {
                    return;
                }
                for (FrameEventEntity frame : batch) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(frame.getId()))
                            .name("frame")
                            .data(json.writeValueAsString(message(frame))));
                    lastSentId = frame.getId();
                    framesReplayedTotal.incrementAndGet();
                    persistCursor(false);
                }
            }
        }

        /** Phase two: the live stream. */
        private void stream() throws Exception {
            while (open) {
                Event event = queue.take();
                // Already sent during replay — the queue was filling while catch-up ran.
                if (event.frameId() <= lastSentId) {
                    continue;
                }
                // Deliberately no MediaType: the payload is already JSON, and handing it to Spring
                // as APPLICATION_JSON would run it through a converter and double-encode it.
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.frameId()))
                        .name(event.name())
                        .data(event.payload()));
                lastSentId = event.frameId();
                framesDistributedTotal.incrementAndGet();
                persistCursor(false);
            }
        }

        /**
         * Writes the cursor for a named subscription, throttled so a high frame rate does not
         * become an equally high write rate. {@code force} is used on disconnect.
         */
        private void persistCursor(boolean force) {
            if (subscription == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!force && now - cursorWrittenAtMillis < CURSOR_WRITE_INTERVAL_MILLIS) {
                return;
            }
            cursorWrittenAtMillis = now;
            try {
                FrameCursorEntity cursor = cursors.findByName(subscription)
                        .orElseGet(() -> new FrameCursorEntity(
                                subscription, source, lastSentId, Instant.now()));
                cursor.rememberSource(source);
                cursor.advanceTo(lastSentId, Instant.now());
                cursors.save(cursor);
            } catch (Exception exception) {
                // A cursor write failing costs redelivery on the next reconnect, which is the
                // documented at-least-once behaviour — it must not kill a live stream.
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("subscription", subscription);
                metadata.put("error", exception.getMessage());
                logger.warn("Could not persist a stream cursor; the client may see frames twice.",
                        metadata);
            }
        }

        private void stop() {
            open = false;
            Thread running = pump;
            if (running != null) {
                running.interrupt();
            }
        }
    }
}

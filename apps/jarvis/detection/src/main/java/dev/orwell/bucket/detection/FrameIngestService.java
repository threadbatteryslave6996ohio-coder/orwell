package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.primitives.Sha256;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The hub's ingest half: accepts a pushed frame, hands it to {@link FrameHub} for the clients
 * connected right now, and queues it for storage so a client that reconnects can be replayed it.
 *
 * <p>Every frame that arrives is kept. The hub does not look inside a frame and does not decide
 * whether one is worth relaying — it receives, stores and redistributes. Whatever a producer thinks
 * is worth pushing is what viewers get, which also means the payload need not be an image the
 * server can decode.
 *
 * <p>The id is allocated from a sequence before either step, so the frame can go out to connected
 * clients <em>before</em> it is written — the live stream never waits on Postgres. Storing happens
 * behind it via {@link FrameStoreWriter} and is what makes a reconnecting client able to catch up.
 * Ingest returns as soon as the frame is queued for each client, never after they have received
 * it, so a producer's frame rate stays decoupled from the slowest viewer's connection.
 */
@Service
public class FrameIngestService {
    private final FrameIdAllocator ids;
    private final FrameStoreWriter store;
    private final FrameHub hub;
    private final AtomicLong framesReceivedTotal = new AtomicLong();

    public FrameIngestService(FrameIdAllocator ids, FrameStoreWriter store, FrameHub hub) {
        this.ids = Objects.requireNonNull(ids, "ids");
        this.store = Objects.requireNonNull(store, "store");
        this.hub = Objects.requireNonNull(hub, "hub");
    }

    public long framesReceivedTotal() {
        return framesReceivedTotal.get();
    }

    /**
     * Accepts one pushed frame. Throws {@link FramePayload.InvalidFrameException} for a malformed
     * envelope — missing base64, undecodable base64, or a hash that does not match the bytes —
     * which the endpoint maps to 400; any other runtime failure surfaces as 500.
     */
    public Map<String, Object> ingest(Map<String, Object> payload) {
        byte[] frameBytes = FramePayload.decode(payload);
        String source = FramePayload.source(payload);
        Object frameIndex = payload.get("frameIndex");
        framesReceivedTotal.incrementAndGet();

        FrameEventEntity frame = new FrameEventEntity(
                ids.next(),
                source,
                asLong(frameIndex),
                Sha256.hex(frameBytes),
                Instant.now(),
                frameBytes);

        // Broadcast first, store second. The id was allocated up front precisely so the live
        // stream never waits on the database — a Postgres stall slows catch-up, not the video.
        int recipients = hub.broadcast(frame);
        store.submit(frame);

        // LinkedHashMap, not Map.of: frameIndex and timestamp are optional request fields and may
        // be null, which Map.of rejects with an NPE.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("source", source);
        response.put("frameIndex", frameIndex);
        response.put("timestamp", payload.get("timestamp"));
        response.put("stored", true);
        response.put("frameId", frame.getId());
        // Lets a producer notice nobody is watching without polling anything. A frame with zero
        // recipients is still stored, so a client that connects later can replay it.
        response.put("recipients", recipients);
        return response;
    }

    /** {@code frameIndex} is free-form in the payload; anything non-numeric is simply not recorded. */
    private static Long asLong(Object frameIndex) {
        if (frameIndex instanceof Number number) {
            return number.longValue();
        }
        try {
            return frameIndex == null ? null : Long.valueOf(String.valueOf(frameIndex).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}

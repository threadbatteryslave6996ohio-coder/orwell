package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.primitives.Sha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The hub's ingest half: accepts a pushed frame, decides whether it is worth keeping, stores it,
 * and hands it to {@link FrameHub} for the clients connected right now.
 *
 * <p>The id is allocated from a sequence before either step, so the frame can go out to connected
 * clients <em>before</em> it is written — the live stream never waits on Postgres. Storing happens
 * behind it via {@link FrameStoreWriter} and is what makes a reconnecting client able to catch up.
 * Ingest returns as soon as the frame is queued for each client, never after they have received
 * it, so a producer's frame rate stays decoupled from the slowest viewer's connection.
 */
@Service
public class FrameIngestService {
    private final MotionService motion;
    private final FrameIdAllocator ids;
    private final FrameStoreWriter store;
    private final FrameHub hub;
    private final boolean changedOnly;
    private final AtomicLong framesReceivedTotal = new AtomicLong();
    private final AtomicLong framesStoredTotal = new AtomicLong();

    public FrameIngestService(
            MotionService motion,
            FrameIdAllocator ids,
            FrameStoreWriter store,
            FrameHub hub,
            @Value("${detection.relay.mode}") String mode
    ) {
        this.motion = Objects.requireNonNull(motion, "motion");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.store = Objects.requireNonNull(store, "store");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.changedOnly = !"all".equalsIgnoreCase(mode);
    }

    public long framesReceivedTotal() {
        return framesReceivedTotal.get();
    }

    public long framesStoredTotal() {
        return framesStoredTotal.get();
    }

    /**
     * Accepts one pushed frame. Throws {@link FramePayload.InvalidFrameException} for a bad frame
     * (mapped to 400 by the endpoint); any other runtime failure surfaces as 500.
     */
    public Map<String, Object> ingest(Map<String, Object> payload) {
        byte[] frameBytes = FramePayload.decode(payload);
        String source = FramePayload.source(payload);
        Object frameIndex = payload.get("frameIndex");
        Object timestamp = payload.get("timestamp");
        framesReceivedTotal.incrementAndGet();

        Map<String, Object> verdict = motion.compare(source, frameBytes, frameIndex, timestamp);
        boolean changed = (boolean) verdict.get("changed");
        boolean firstFrame = (boolean) verdict.get("firstFrame");
        // A source's first frame is always kept even in `changed` mode: it is the baseline a
        // viewer needs before any later "this differs" frame means anything to it.
        boolean kept = !changedOnly || changed || firstFrame;

        Map<String, Object> response = new LinkedHashMap<>(verdict);
        if (!kept) {
            response.put("stored", false);
            response.put("frameId", null);
            response.put("recipients", 0);
            return response;
        }

        FrameEventEntity frame = new FrameEventEntity(
                ids.next(),
                source,
                asLong(frameIndex),
                Sha256.hex(frameBytes),
                changed,
                (double) verdict.get("changedFraction"),
                Instant.now(),
                frameBytes);

        // Broadcast first, store second. The id was allocated up front precisely so the live
        // stream never waits on the database — a Postgres stall slows catch-up, not the video.
        int recipients = hub.broadcast(frame);
        store.submit(frame);
        framesStoredTotal.incrementAndGet();

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

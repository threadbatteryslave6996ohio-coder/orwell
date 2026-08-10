package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.primitives.Sha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bastion's ingest half: accepts a pushed frame, decides whether it is worth keeping, and
 * appends it to the log that {@link FrameDeliveryJob} fans out from.
 *
 * <p>Ingest returns as soon as the row is committed. It deliberately does not push to subscribers
 * inline — a producer's frame rate must not be coupled to the slowest subscriber's response time,
 * which is exactly what a synchronous fan-out would do.
 */
@Service
public class FrameIngestService {
    private final MotionService motion;
    private final FrameEventRepository events;
    private final boolean changedOnly;
    private final AtomicLong framesReceivedTotal = new AtomicLong();
    private final AtomicLong framesStoredTotal = new AtomicLong();

    public FrameIngestService(
            MotionService motion,
            FrameEventRepository events,
            @Value("${detection.fanout.mode}") String mode
    ) {
        this.motion = Objects.requireNonNull(motion, "motion");
        this.events = Objects.requireNonNull(events, "events");
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
        // subscriber needs before any later "this differs" event means anything to it.
        boolean stored = !changedOnly || changed || firstFrame;

        Map<String, Object> response = new LinkedHashMap<>(verdict);
        if (stored) {
            FrameEventEntity saved = events.save(new FrameEventEntity(
                    source,
                    asLong(frameIndex),
                    Sha256.hex(frameBytes),
                    changed,
                    (double) verdict.get("changedFraction"),
                    Instant.now(),
                    frameBytes));
            framesStoredTotal.incrementAndGet();
            response.put("frameId", saved.getId());
        } else {
            response.put("frameId", null);
        }
        response.put("stored", stored);
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

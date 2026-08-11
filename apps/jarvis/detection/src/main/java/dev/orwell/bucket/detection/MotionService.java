package dev.orwell.bucket.detection;

import dev.orwell.env.Env;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Frame-to-frame change detection: answers whether the frame in the request differs from the
 * previous frame seen for the same source. Unlike {@link DetectionService} this is stateful —
 * a single frame means nothing on its own — so it keeps one fingerprint per source.
 */
@Service
public class MotionService {
    /**
     * Cap on tracked sources. {@code source} is read straight off the request body, so an
     * unbounded map would let a caller grow the heap one invented source name at a time. The
     * cache is access-ordered, so eviction falls on the sources that stopped sending frames.
     */
    private static final int MAX_TRACKED_SOURCES = 64;

    private final FrameChangeDetector detector;
    private final Map<String, int[]> previousBySource = new SourceCache();
    // Atomic for the same reason as DetectionService's counters: Spring MVC serves this endpoint
    // from Tomcat's concurrent request pool.
    private final AtomicInteger framesComparedTotal = new AtomicInteger();
    private final AtomicInteger changesDetectedTotal = new AtomicInteger();

    public MotionService(
            @Value("${detection.motion.cell-threshold}") int cellThreshold,
            @Value("${detection.motion.min-changed-fraction}") double minChangedFraction
    ) {
        this.detector = new FrameChangeDetector(cellThreshold, minChangedFraction);
    }

    static MotionService fromEnv(Env env) {
        return new MotionService(
                env.get(DetectionEnvs.DETECTION_MOTION_CELL_THRESHOLD),
                env.get(DetectionEnvs.DETECTION_MOTION_MIN_CHANGED_FRACTION)
        );
    }

    public int framesComparedTotal() {
        return framesComparedTotal.get();
    }

    public int changesDetectedTotal() {
        return changesDetectedTotal.get();
    }

    /**
     * Compares the payload's frame against the last one for its source and reports whether
     * anything changed. Throws {@link FramePayload.InvalidFrameException} for a bad frame
     * (mapped to 400 by the endpoint); any other runtime failure surfaces as 500.
     */
    public Map<String, Object> motion(Map<String, Object> payload) {
        String source = FramePayload.source(payload);
        Object frameIndex = payload.get("frameIndex");
        Object timestamp = payload.get("timestamp");
        int[] current = FrameChangeDetector.fingerprint(FramePayload.decode(payload));

        int[] previous;
        synchronized (previousBySource) {
            previous = previousBySource.put(source, current);
        }

        // LinkedHashMap, not Map.of: frameIndex/timestamp are optional request fields and may be
        // null, which Map.of rejects with an NPE.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("source", source);
        response.put("frameIndex", frameIndex);
        response.put("timestamp", timestamp);
        response.put("totalCells", FrameChangeDetector.CELLS);

        if (previous == null) {
            // The first frame for a source has nothing to compare against. Reporting "no change"
            // is the honest answer; reporting a change would fire an event on every stream start.
            response.put("changed", false);
            response.put("firstFrame", true);
            response.put("changedCells", 0);
            response.put("changedFraction", 0.0);
            return response;
        }

        FrameChangeDetector.Comparison comparison = detector.compare(previous, current);
        framesComparedTotal.incrementAndGet();
        if (comparison.changed()) {
            changesDetectedTotal.incrementAndGet();
        }
        response.put("changed", comparison.changed());
        response.put("firstFrame", false);
        response.put("changedCells", comparison.changedCells());
        response.put("changedFraction", comparison.changedFraction());
        return response;
    }

    /** Access-ordered LRU of per-source fingerprints, bounded by {@link #MAX_TRACKED_SOURCES}. */
    private static final class SourceCache extends LinkedHashMap<String, int[]> {
        private SourceCache() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> eldest) {
            return size() > MAX_TRACKED_SOURCES;
        }
    }
}

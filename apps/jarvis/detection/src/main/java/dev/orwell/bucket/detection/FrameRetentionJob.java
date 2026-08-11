package dev.orwell.bucket.detection;

import dev.orwell.bucket.retention.RetentionPolicy;
import dev.orwell.bucket.retention.RetentionSweeper;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps {@code frame_events} inside its budget.
 *
 * <p>This is what makes storing video bytes in Postgres survivable, so it runs unconditionally and
 * ignores delivery state. A client that has been away longer than the retained history loses the
 * frames that aged out — bounded storage is chosen over guaranteed catch-up, because the
 * alternative is a table that grows at the ingest rate until the disk fills, on an instance shared
 * with every other app in the repo.
 *
 * <p>Two bounds, whichever bites first: {@code DETECTION_FRAME_MAX_BYTES} caps the frame bytes
 * retained, and {@code DETECTION_FRAME_RETENTION_SECONDS} caps how stale a retained frame may be.
 * The byte budget is the one that protects the disk on a busy stream; the age bound is what stops
 * a quiet camera's frames from living forever. Because the byte budget is what usually binds, the
 * replay window is <em>variable</em>: a busy hour buys less history than a quiet one, and the
 * footprint is the thing held constant.
 *
 * <p>The sweep itself lives in {@code jarvis-retention} and owns its own JDBC transactions. This
 * class is only the schedule and the counters.
 */
@Component
public class FrameRetentionJob {
    private final RetentionSweeper sweeper;
    private final RetentionPolicy policy;
    private final Logger logger;
    private final AtomicLong framesDroppedByAgeTotal = new AtomicLong();
    private final AtomicLong framesDroppedByBudgetTotal = new AtomicLong();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicReference<String> lastSweepError = new AtomicReference<>();

    public FrameRetentionJob(
            DataSource dataSource,
            @Value("${detection.frame-retention-seconds}") int retentionSeconds,
            @Value("${detection.frame-max-bytes}") long maxBytes,
            Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.sweeper = new RetentionSweeper(dataSource, logger);
        this.policy = RetentionPolicy.of(
                "frame_events", "id", "captured_at", "frame_bytes",
                maxBytes,
                retentionSeconds > 0 ? Duration.ofSeconds(retentionSeconds) : null);
    }

    public long framesDroppedByAgeTotal() {
        return framesDroppedByAgeTotal.get();
    }

    public long framesDroppedByBudgetTotal() {
        return framesDroppedByBudgetTotal.get();
    }

    /** Frame bytes currently replayable, as of the last sweep. */
    public long retainedBytes() {
        return retainedBytes.get();
    }

    /** Null when the last sweep succeeded. Surfaced on /health so a broken sweep is visible. */
    public String lastSweepError() {
        return lastSweepError.get();
    }

    @Scheduled(fixedRateString = "${detection.retention-sweep-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledSweep() {
        try {
            sweep();
        } catch (Exception exception) {
            // A failed sweep is not fatal to serving frames, but it is fatal to the disk if it
            // keeps failing, so it is recorded where an operator will see it rather than only
            // scrolling past in the log.
            lastSweepError.set(exception.getMessage());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("Frame retention sweep failed; will retry next interval.", metadata);
        }
    }

    /** One retention pass. Package-visible so tests can drive a sweep. */
    void sweep() throws Exception {
        var result = sweeper.sweep(policy, Instant.now());
        framesDroppedByAgeTotal.addAndGet(result.rowsDroppedByAge());
        framesDroppedByBudgetTotal.addAndGet(result.rowsDroppedByBytes());
        retainedBytes.set(result.bytesAfter());
        lastSweepError.set(null);
        if (result.rowsDropped() > 0) {
            logger.debug("Dropped frames past the retention bounds.", result.asMetadata());
        }
    }
}

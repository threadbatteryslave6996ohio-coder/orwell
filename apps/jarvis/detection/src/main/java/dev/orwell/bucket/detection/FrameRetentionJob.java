package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Drops frames older than the retention window.
 *
 * <p>This is what makes storing video bytes in Postgres survivable, so it runs unconditionally and
 * ignores delivery state. A subscriber that has been down longer than the window loses the frames
 * that aged out — bounded storage is chosen over guaranteed catch-up, because the alternative is a
 * table that grows at the ingest rate until the disk fills. Widen
 * {@code DETECTION_FRAME_RETENTION_SECONDS} to trade disk for tolerance.
 */
@Component
public class FrameRetentionJob {
    private final FrameEventRepository events;
    private final int retentionSeconds;
    private final Logger logger;

    public FrameRetentionJob(
            FrameEventRepository events,
            @Value("${detection.frame-retention-seconds}") int retentionSeconds,
            Logger logger) {
        this.events = Objects.requireNonNull(events, "events");
        this.retentionSeconds = retentionSeconds;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Scheduled(fixedRateString = "${detection.retention-sweep-seconds}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
    void scheduledSweep() {
        try {
            sweep();
        } catch (Exception exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("Frame retention sweep failed; will retry next interval.", metadata);
        }
    }

    /** One retention pass. Package-visible so tests can drive a sweep. @return rows deleted. */
    @Transactional
    int sweep() {
        Instant cutoff = Instant.now().minus(retentionSeconds, ChronoUnit.SECONDS);
        int deleted = events.deleteCapturedBefore(cutoff);
        if (deleted > 0) {
            logger.debug("Dropped frames past the retention window.", Map.of(
                    "deleted", deleted,
                    "retentionSeconds", retentionSeconds));
        }
        return deleted;
    }
}

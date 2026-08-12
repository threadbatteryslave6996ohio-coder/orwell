package dev.orwell.bucket.retention.worker;

import dev.orwell.bucket.retention.RetentionPolicy;
import dev.orwell.bucket.retention.RetentionSweeper;
import dev.orwell.bucket.retention.SweepResult;
import dev.orwell.logging.Logger;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the {@code frame_events} sweep on a timer, forever.
 *
 * <p>Keeps the counters the hub's {@code /health} used to carry. They are reported to the log
 * rather than served, because this process has no port — see {@link RetentionWorker} for why that
 * is the trade being made. A sweep that starts failing is the thing worth alerting on, so it is
 * logged at WARN every interval it stays broken rather than once when it breaks.
 *
 * <p>One thread, and {@code scheduleWithFixedDelay} rather than {@code scheduleAtFixedRate}: if a
 * sweep runs longer than the interval — a first sweep against a table that grew while the worker
 * was down will — fixed-rate would queue the next one immediately and keep a second delete running
 * against a table the first is still deleting from.
 */
final class FrameRetentionSchedule implements AutoCloseable {
    private final RetentionSweeper sweeper;
    private final RetentionPolicy policy;
    private final Logger logger;
    private final int sweepSeconds;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "frame-retention-sweep");
                thread.setDaemon(false);
                return thread;
            });
    private final AtomicLong framesDroppedByAgeTotal = new AtomicLong();
    private final AtomicLong framesDroppedByBudgetTotal = new AtomicLong();
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicReference<String> lastSweepError = new AtomicReference<>();

    FrameRetentionSchedule(DataSource dataSource, Logger logger, long maxBytes, int maxAgeSeconds,
            int sweepSeconds) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.sweeper = new RetentionSweeper(dataSource, logger);
        this.sweepSeconds = sweepSeconds;
        this.policy = RetentionPolicy.of(
                "frame_events", "id", "captured_at", "frame_bytes",
                maxBytes,
                maxAgeSeconds > 0 ? Duration.ofSeconds(maxAgeSeconds) : null);
    }

    long framesDroppedByAgeTotal() {
        return framesDroppedByAgeTotal.get();
    }

    long framesDroppedByBudgetTotal() {
        return framesDroppedByBudgetTotal.get();
    }

    /** Frame bytes currently replayable, as of the last sweep. */
    long retainedBytes() {
        return retainedBytes.get();
    }

    /** Null when the last sweep succeeded. */
    String lastSweepError() {
        return lastSweepError.get();
    }

    void start() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("table", "frame_events");
        metadata.put("sweepSeconds", sweepSeconds);
        metadata.put("maxBytes", policy.maxBytes());
        metadata.put("maxAge", String.valueOf(policy.maxAge()));
        logger.info("Retention worker started; sweeping on a fixed delay.", metadata);
        scheduler.scheduleWithFixedDelay(
                this::scheduledSweep, 0, sweepSeconds, TimeUnit.SECONDS);
    }

    /** What the scheduler runs. Package-visible so a test can prove a failure does not escape. */
    void scheduledSweep() {
        try {
            sweep();
        } catch (Exception exception) {
            // Caught rather than propagated: an exception out of a scheduled task cancels the
            // schedule silently, which would leave a live worker that never sweeps again — the
            // exact failure shape this whole component exists to make impossible.
            lastSweepError.set(exception.getMessage());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("Frame retention sweep failed; will retry next interval.", metadata);
        }
    }

    /** One retention pass. Package-visible so tests can drive a sweep without waiting. */
    void sweep() throws Exception {
        SweepResult result = sweeper.sweep(policy, Instant.now());
        framesDroppedByAgeTotal.addAndGet(result.rowsDroppedByAge());
        framesDroppedByBudgetTotal.addAndGet(result.rowsDroppedByBytes());
        retainedBytes.set(result.bytesAfter());
        lastSweepError.set(null);
        if (result.rowsDropped() > 0) {
            logger.debug("Dropped frames past the retention bounds.", result.asMetadata());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

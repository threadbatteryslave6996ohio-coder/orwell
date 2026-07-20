package dev.orwell.clients.sync;

import dev.orwell.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Drives the offline clipboard synchronization loop: waits for the offline file to appear, hands each
 * snapshot to an {@link OfflineSyncService}, clears the file once a snapshot is fully synchronized, and
 * applies exponential backoff (via {@link SyncRetryPolicy}) to transient failures. The actual file,
 * sleep, and logging operations are injected so the loop stays testable.
 */
final class SyncMonitor {
    static final Duration DEFAULT_SYNC_INTERVAL = Duration.ofMinutes(30);
    static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
    static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);
    static final int MAX_RETRIES = 5;
    private static final SyncRetryPolicy RETRY_POLICY = new SyncRetryPolicy(
            MAX_RETRIES, INITIAL_RETRY_DELAY, MAX_RETRY_DELAY);

    private final OfflineSyncService syncService;
    private final String clientId;
    private final Logger logger;

    SyncMonitor(OfflineSyncService syncService, String clientId, Logger logger) {
        this.syncService = syncService;
        this.clientId = clientId;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void monitor(RecordSource recordSource, RejectionSink rejectionSink, SnapshotClearer snapshotClearer,
                 ClipboardSnapshot initialSnapshot, Duration interval, Sleeper sleeper)
            throws InterruptedException {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Sync interval must be positive.");
        }

        ClipboardSnapshot snapshot = initialSnapshot;
        String lastProcessedContent = null;
        int failures = 0;
        while (true) {
            if (!snapshot.content().equals(lastProcessedContent)) {
                try {
                    rejectAll(snapshot.rejections(), rejectionSink);
                    List<ClipboardRecord> records = snapshot.records();
                    if (records.isEmpty()) {
                        logger.info("No offline clipboard entries to sync. Waiting for changes.");
                    } else {
                        SyncResult result = syncService.sync(records, rejectionSink);
                        logger.info("Offline clipboard sync complete.", Map.of(
                                "clientId", clientId,
                                "checked", records.size(),
                                "alreadyPresent", result.alreadyPresent(),
                                "sent", result.sent(),
                                "rejected", result.rejected()));
                    }
                    if (snapshotClearer.clear(snapshot)) {
                        logger.info("Cleared synchronized offline clipboard file.");
                    } else {
                        logger.info("Offline clipboard file changed during sync; preserving it for the next pass.");
                    }
                    lastProcessedContent = snapshot.content();
                    failures = 0;
                } catch (IOException | RuntimeException exception) {
                    failures = nextFailureCount(failures, "synchronize offline clipboard", exception);
                    Duration delay = retryDelay(failures);
                    logger.warn("Offline clipboard sync failed; retrying.",
                            retryMetadata(failures, delay, exception));
                    sleeper.sleep(delay);
                    continue;
                }
            }

            sleeper.sleep(interval);
            snapshot = readWithRetries(recordSource, sleeper, "read offline clipboard file", logger);
        }
    }

    static ClipboardSnapshot awaitInitialSnapshot(RecordSource recordSource, Sleeper sleeper, Logger logger)
            throws InterruptedException {
        int failures = 0;
        while (true) {
            try {
                return recordSource.read();
            } catch (IOException | RuntimeException exception) {
                failures = nextFailureCount(failures, "read initial offline clipboard file", exception);
                Duration delay = retryDelay(failures);
                logger.warn("Could not read initial offline clipboard file; retrying.",
                        retryMetadata(failures, delay, exception));
                sleeper.sleep(delay);
            }
        }
    }

    static ClipboardSnapshot awaitInitialSyncableSnapshot(
            RecordSource recordSource,
            RejectionSink rejectionSink,
            SnapshotClearer snapshotClearer,
            Duration interval,
            Sleeper sleeper,
            Logger logger)
            throws InterruptedException {
        int failures = 0;
        while (true) {
            ClipboardSnapshot snapshot = awaitInitialSnapshot(recordSource, sleeper, logger);
            if (!OfflineSyncService.syncableRecords(snapshot.records()).isEmpty()) {
                return snapshot;
            }
            try {
                rejectAll(snapshot.rejections(), rejectionSink);
                if (!snapshotClearer.clear(snapshot)) {
                    continue;
                }
                failures = 0;
            } catch (IOException | RuntimeException exception) {
                failures = nextFailureCount(failures, "prepare initial offline clipboard snapshot", exception);
                Duration delay = retryDelay(failures);
                logger.warn("Could not prepare initial offline clipboard snapshot; retrying.",
                        retryMetadata(failures, delay, exception));
                sleeper.sleep(delay);
                continue;
            }
            logger.info("Offline clipboard file has no usable clipboard entries yet; waiting to derive CLIENT_ID.",
                    Map.of("waitMinutes", interval.toMinutes()));
            sleeper.sleep(interval);
        }
    }

    private static ClipboardSnapshot readWithRetries(
            RecordSource recordSource, Sleeper sleeper, String operation, Logger logger)
            throws InterruptedException {
        int failures = 0;
        while (true) {
            try {
                return recordSource.read();
            } catch (IOException | RuntimeException exception) {
                failures = nextFailureCount(failures, operation, exception);
                Duration delay = retryDelay(failures);
                Map<String, Object> metadata = retryMetadata(failures, delay, exception);
                metadata.put("operation", operation);
                logger.warn("Offline clipboard operation failed; retrying.", metadata);
                sleeper.sleep(delay);
            }
        }
    }

    static Duration retryDelay(int retryNumber) {
        return RETRY_POLICY.delay(retryNumber);
    }

    private static Map<String, Object> retryMetadata(int failures, Duration delay, Exception cause) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retry", failures);
        metadata.put("maxRetries", MAX_RETRIES);
        metadata.put("retryInSeconds", delay.toSeconds());
        metadata.put("error", cause.getMessage());
        return metadata;
    }

    private static int nextFailureCount(int failures, String operation, Exception cause) {
        return RETRY_POLICY.nextFailure(failures, operation, cause);
    }

    private static void rejectAll(List<RejectedRecord> rejections, RejectionSink rejectionSink) throws IOException {
        for (RejectedRecord rejection : rejections) {
            rejectionSink.reject(rejection);
        }
    }

    @FunctionalInterface
    interface RecordSource {
        ClipboardSnapshot read() throws IOException;
    }

    @FunctionalInterface
    interface SnapshotClearer {
        boolean clear(ClipboardSnapshot snapshot) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}

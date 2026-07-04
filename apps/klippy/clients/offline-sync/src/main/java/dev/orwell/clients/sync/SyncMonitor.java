package dev.orwell.clients.sync;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Drives the offline clipboard synchronization loop: waits for the offline file to appear, hands each
 * snapshot to an {@link OfflineSyncService}, clears the file once a snapshot is fully synchronized, and
 * applies exponential backoff (via {@link SyncRetryPolicy}) to transient failures. The actual file and
 * sleep operations are injected so the loop stays testable.
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

    SyncMonitor(OfflineSyncService syncService, String clientId) {
        this.syncService = syncService;
        this.clientId = clientId;
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
                        System.out.println("No offline clipboard entries to sync. Waiting for changes.");
                    } else {
                        SyncResult result = syncService.sync(records, rejectionSink);
                        System.out.printf("Offline clipboard sync complete. clientId=%s checked=%d alreadyPresent=%d sent=%d rejected=%d%n",
                                clientId, records.size(), result.alreadyPresent(), result.sent(), result.rejected());
                    }
                    if (snapshotClearer.clear(snapshot)) {
                        System.out.println("Cleared synchronized offline clipboard file.");
                    } else {
                        System.out.println("Offline clipboard file changed during sync; preserving it for the next pass.");
                    }
                    lastProcessedContent = snapshot.content();
                    failures = 0;
                } catch (IOException | RuntimeException exception) {
                    failures = nextFailureCount(failures, "synchronize offline clipboard", exception);
                    Duration delay = retryDelay(failures);
                    System.err.printf("Offline clipboard sync failed; retry %d/%d in %d seconds: %s%n",
                            failures, MAX_RETRIES, delay.toSeconds(), exception.getMessage());
                    sleeper.sleep(delay);
                    continue;
                }
            }

            sleeper.sleep(interval);
            snapshot = readWithRetries(recordSource, sleeper, "read offline clipboard file");
        }
    }

    static ClipboardSnapshot awaitInitialSnapshot(RecordSource recordSource, Sleeper sleeper)
            throws InterruptedException {
        int failures = 0;
        while (true) {
            try {
                return recordSource.read();
            } catch (IOException | RuntimeException exception) {
                failures = nextFailureCount(failures, "read initial offline clipboard file", exception);
                Duration delay = retryDelay(failures);
                System.err.printf("Could not read initial offline clipboard file; retry %d/%d in %d seconds: %s%n",
                        failures, MAX_RETRIES, delay.toSeconds(), exception.getMessage());
                sleeper.sleep(delay);
            }
        }
    }

    static ClipboardSnapshot awaitInitialSyncableSnapshot(
            RecordSource recordSource,
            RejectionSink rejectionSink,
            SnapshotClearer snapshotClearer,
            Duration interval,
            Sleeper sleeper)
            throws InterruptedException {
        int failures = 0;
        while (true) {
            ClipboardSnapshot snapshot = awaitInitialSnapshot(recordSource, sleeper);
            if (!OfflineSyncService.syncableRecords(snapshot.records(), false).isEmpty()) {
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
                System.err.printf("Could not prepare initial offline clipboard snapshot; retry %d/%d in %d seconds: %s%n",
                        failures, MAX_RETRIES, delay.toSeconds(), exception.getMessage());
                sleeper.sleep(delay);
                continue;
            }
            System.out.printf("Offline clipboard file has no usable clipboard entries yet; waiting %d minutes to derive CLIENT_ID.%n",
                    interval.toMinutes());
            sleeper.sleep(interval);
        }
    }

    private static ClipboardSnapshot readWithRetries(RecordSource recordSource, Sleeper sleeper, String operation)
            throws InterruptedException {
        int failures = 0;
        while (true) {
            try {
                return recordSource.read();
            } catch (IOException | RuntimeException exception) {
                failures = nextFailureCount(failures, operation, exception);
                Duration delay = retryDelay(failures);
                System.err.printf("Could not %s; retry %d/%d in %d seconds: %s%n",
                        operation, failures, MAX_RETRIES, delay.toSeconds(), exception.getMessage());
                sleeper.sleep(delay);
            }
        }
    }

    static Duration retryDelay(int retryNumber) {
        return RETRY_POLICY.delay(retryNumber);
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

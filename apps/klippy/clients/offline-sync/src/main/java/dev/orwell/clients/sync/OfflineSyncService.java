package dev.orwell.clients.sync;

import dev.orwell.utils.ClipboardLimits;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * Synchronizes a batch of offline clipboard records against the server through a
 * {@link RemoteClipboardGateway}. Owns the synchronization policy: drop oversized entries, reject
 * entries owned by another client, skip entries already present remotely, and dead-letter entries the
 * server permanently rejects. Transient failures surface as exceptions for the caller to retry.
 */
final class OfflineSyncService {
    private final RemoteClipboardGateway gateway;
    private final String clientId;

    OfflineSyncService(RemoteClipboardGateway gateway, String clientId) {
        this.gateway = gateway;
        this.clientId = clientId;
    }

    SyncResult sync(List<ClipboardRecord> records) throws IOException, InterruptedException {
        return sync(records, rejection -> {
            throw new IOException("A rejection sink is required to preserve rejected offline entries.");
        });
    }

    SyncResult sync(List<ClipboardRecord> records, RejectionSink rejectionSink)
            throws IOException, InterruptedException {
        records = syncableRecords(records, true);
        if (records.isEmpty()) {
            return new SyncResult(0, 0, 0);
        }

        List<ClipboardRecord> ownedRecords = records.stream()
                .filter(record -> record.clientId().equals(clientId))
                .toList();
        for (ClipboardRecord record : records) {
            if (!record.clientId().equals(clientId)) {
                rejectionSink.reject(RejectedRecord.forRecord(
                        "client-ownership", "Entry belongs to a different client", record));
            }
        }
        int rejected = records.size() - ownedRecords.size();
        records = ownedRecords;
        if (records.isEmpty()) {
            return new SyncResult(0, 0, rejected);
        }

        Instant from = records.stream().map(ClipboardRecord::timestamp).min(Instant::compareTo).orElseThrow();
        Instant to = records.stream().map(ClipboardRecord::timestamp).max(Instant::compareTo).orElseThrow();
        RemoteRecordIndex remoteRecords = gateway.fetchPresentRecords(from.minusNanos(1_000), to.plusNanos(1_000));
        int alreadyPresent = 0;
        int sent = 0;
        for (ClipboardRecord record : records) {
            if (remoteRecords.contains(record)) {
                alreadyPresent++;
                continue;
            }
            HttpResponse<String> response = gateway.send(record);
            if (isPermanentRecordRejection(response.statusCode())) {
                rejectionSink.reject(RejectedRecord.forRecord(
                        "server-response", "HTTP " + response.statusCode(), record));
                rejected++;
                continue;
            }
            RemoteClipboardGateway.requireSuccess(response, "send offline clipboard entry at " + record.timestamp());
            remoteRecords.add(record);
            sent++;
        }
        return new SyncResult(alreadyPresent, sent, rejected);
    }

    static List<ClipboardRecord> syncableRecords(List<ClipboardRecord> records, boolean logIgnored) {
        return records.stream()
                .filter(record -> {
                    if (ClipboardLimits.isWithinContentLimit(record.content())) {
                        return true;
                    }
                    if (logIgnored) {
                        System.err.printf("Ignoring oversized offline clipboard entry. chars=%d maxChars=%d%n",
                                record.content().length(), ClipboardLimits.MAX_CONTENT_CHARACTERS);
                    }
                    return false;
                })
                .toList();
    }

    private static boolean isPermanentRecordRejection(int statusCode) {
        return statusCode == 400 || statusCode == 413 || statusCode == 415 || statusCode == 422;
    }
}

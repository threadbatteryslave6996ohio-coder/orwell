package dev.orwell.clients.sync;

import dev.orwell.clients.core.ClientAuthInitializer;
import dev.orwell.clients.core.OfflineFileLockerFactory;
import dev.orwell.clients.core.OfflineLogPath;
import dev.orwell.clients.core.env.ClientAuthSession;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.env.Env;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles and runs the offline sync client from an {@link OfflineSyncConfig}. Owns the
 * ordering that {@code main()} used to inline: build the file-locker sources, wait for the
 * first snapshot, resolve the client id, initialize auth, then start the monitor loop.
 */
public final class OfflineSyncBootstrap {
    private static final Path DEFAULT_OFFLINE_LOG = OfflineLogPath.DEFAULT;

    private final OfflineSyncConfig config;
    private final OfflineFileLockerClient fileLocker;

    public OfflineSyncBootstrap(OfflineSyncConfig config, OfflineFileLockerClient fileLocker) {
        this.config = Objects.requireNonNull(config, "config");
        this.fileLocker = Objects.requireNonNull(fileLocker, "fileLocker");
    }

    public static OfflineSyncBootstrap fromArgs(String[] args) throws java.io.IOException {
        // Only the default location migrates: an explicit path is the caller's choice, and
        // nothing should be moved out from under it.
        if (args.length == 0) {
            OfflineLogPath.migrateLegacyIfPresent();
        }
        Path offlineLog = args.length == 0 ? DEFAULT_OFFLINE_LOG : Path.of(args[0]);
        Env env = ClientEnvs.load();
        OfflineFileLockerClient fileLocker = OfflineFileLockerFactory.create(env);
        return new OfflineSyncBootstrap(OfflineSyncConfig.load(env, offlineLog), fileLocker);
    }

    public void run() throws Exception {
        SyncMonitor.RecordSource recordSource =
                () -> OfflineSnapshotParser.parseSnapshot(fileLocker.read(config.offlineLog()), config.offlineLog());
        RejectionSink rejectionSink = rejection -> fileLocker.append(config.deadLetterLog(), rejection.toJson());
        SyncMonitor.SnapshotClearer snapshotClearer =
                snapshot -> fileLocker.clearIfUnchanged(config.offlineLog(), snapshot.content());

        ClipboardSnapshot initialSnapshot = config.clientIdConfigured()
                ? SyncMonitor.awaitInitialSnapshot(recordSource, Thread::sleep)
                : SyncMonitor.awaitInitialSyncableSnapshot(
                        recordSource, rejectionSink, snapshotClearer, config.syncInterval(), Thread::sleep);

        String clientId = resolveClientId(initialSnapshot);
        ClientAuthSession authSession = initializeAuth(clientId);

        RemoteClipboardGateway gateway = new RemoteClipboardGateway(config.endpoint(), authSession, clientId);
        OfflineSyncService syncService = new OfflineSyncService(gateway, clientId);
        SyncMonitor monitor = new SyncMonitor(syncService, clientId);
        System.out.printf("Monitoring %s for offline clipboard changes every %d minutes.%n",
                config.offlineLog().toAbsolutePath(), config.syncInterval().toMinutes());
        monitor.monitor(recordSource, rejectionSink, snapshotClearer, initialSnapshot, config.syncInterval(), Thread::sleep);
    }

    private String resolveClientId(ClipboardSnapshot initialSnapshot) {
        if (config.clientIdConfigured()) {
            return config.configuredClientId();
        }
        List<ClipboardRecord> syncableRecords =
                OfflineSyncService.syncableRecords(initialSnapshot.records(), false);
        return singleClientId(syncableRecords);
    }

    private ClientAuthSession initializeAuth(String clientId) {
        ClientAuthSession authSession =
                new ClientAuthSession(config.authServerUrl(), clientId, config.clientSecret(), config.clientToken());
        try {
            ClientAuthInitializer.initialize(authSession, config.authServerUrl(), clientId);
        } catch (IllegalStateException exception) {
            if (authSession.canRefresh() || authSession.hasToken()) {
                throw exception;
            }
            throw new IllegalStateException("Set CLIENT_SECRET or CLIENT_TOKEN before syncing.");
        }
        return authSession;
    }

    private static String singleClientId(List<ClipboardRecord> records) {
        Set<String> clientIds = records.stream().map(ClipboardRecord::clientId).collect(Collectors.toSet());
        if (clientIds.size() != 1) {
            throw new IllegalStateException("CLIENT_ID is required when the offline file contains multiple client IDs.");
        }
        return clientIds.iterator().next();
    }
}

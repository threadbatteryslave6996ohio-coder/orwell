package dev.orwell.clients.sync;

import dev.orwell.clients.core.ClientAuthInitializer;
import dev.orwell.clients.core.OfflineFileLockerFactory;
import dev.orwell.clients.core.OfflineLogPath;
import dev.orwell.clients.core.env.ClientAuthSession;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.env.Env;
import dev.orwell.logging.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private final Logger logger;

    public OfflineSyncBootstrap(OfflineSyncConfig config, OfflineFileLockerClient fileLocker, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.fileLocker = Objects.requireNonNull(fileLocker, "fileLocker");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public static OfflineSyncBootstrap fromArgs(String[] args, Logger logger) throws java.io.IOException {
        Path offlineLog = args.length == 0 ? DEFAULT_OFFLINE_LOG : Path.of(args[0]);
        Env env = ClientEnvs.load();
        OfflineFileLockerClient fileLocker = OfflineFileLockerFactory.create(env);
        return new OfflineSyncBootstrap(OfflineSyncConfig.load(env, offlineLog), fileLocker, logger);
    }

    public void run() throws Exception {
        SyncMonitor.RecordSource recordSource =
                () -> OfflineSnapshotParser.parseSnapshot(fileLocker.read(config.offlineLog()), config.offlineLog());
        RejectionSink rejectionSink = rejection -> fileLocker.append(config.deadLetterLog(), rejection.toJson());
        SyncMonitor.SnapshotClearer snapshotClearer =
                snapshot -> fileLocker.clearIfUnchanged(config.offlineLog(), snapshot.content());

        ClipboardSnapshot initialSnapshot = config.clientIdConfigured()
                ? SyncMonitor.awaitInitialSnapshot(recordSource, Thread::sleep, logger)
                : SyncMonitor.awaitInitialSyncableSnapshot(
                        recordSource, rejectionSink, snapshotClearer, config.syncInterval(), Thread::sleep, logger);

        String clientId = resolveClientId(initialSnapshot);
        ClientAuthSession authSession = initializeAuth(clientId);

        RemoteClipboardGateway gateway = new RemoteClipboardGateway(config.endpoint(), authSession, clientId);
        OfflineSyncService syncService = new OfflineSyncService(gateway, clientId, logger);
        SyncMonitor monitor = new SyncMonitor(syncService, clientId, logger);
        logger.info("Monitoring offline clipboard file for changes.", Map.of(
                "offlineLog", String.valueOf(config.offlineLog().toAbsolutePath()),
                "intervalMinutes", config.syncInterval().toMinutes()));
        monitor.monitor(recordSource, rejectionSink, snapshotClearer, initialSnapshot, config.syncInterval(), Thread::sleep);
    }

    private String resolveClientId(ClipboardSnapshot initialSnapshot) {
        if (config.clientIdConfigured()) {
            return config.configuredClientId();
        }
        List<ClipboardRecord> syncableRecords =
                OfflineSyncService.syncableRecords(initialSnapshot.records());
        return singleClientId(syncableRecords);
    }

    private ClientAuthSession initializeAuth(String clientId) {
        ClientAuthSession authSession =
                new ClientAuthSession(config.authServerUrl(), clientId, config.clientSecret(), config.clientToken());
        try {
            ClientAuthInitializer.initialize(authSession, config.authServerUrl(), clientId, logger);
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

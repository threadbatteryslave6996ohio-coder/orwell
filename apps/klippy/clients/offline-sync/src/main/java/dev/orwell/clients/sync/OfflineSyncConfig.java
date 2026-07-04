package dev.orwell.clients.sync;

import dev.orwell.clients.core.ClipboardEndpoint;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Immutable startup configuration for the offline sync client, parsed from the offline-log
 * argument and the environment. Holds every value that can be resolved before the first
 * snapshot is read; the client id is resolved later when {@code CLIENT_ID} is not set.
 */
public record OfflineSyncConfig(
        Path offlineLog,
        Path deadLetterLog,
        URI endpoint,
        Duration syncInterval,
        boolean clientIdConfigured,
        String configuredClientId,
        String authServerUrl,
        String clientSecret,
        String clientToken
) {
    public OfflineSyncConfig {
        Objects.requireNonNull(offlineLog, "offlineLog");
        Objects.requireNonNull(deadLetterLog, "deadLetterLog");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(syncInterval, "syncInterval");
    }

    public static OfflineSyncConfig load(Env env, Path offlineLog) {
        boolean clientIdConfigured = env.has(ClientEnvs.CLIENT_ID);
        Duration syncInterval = env.has(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES)
                ? syncInterval(env.get(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES))
                : SyncMonitor.DEFAULT_SYNC_INTERVAL;
        return new OfflineSyncConfig(
                offlineLog,
                deadLetterPath(offlineLog),
                ClipboardEndpoint.from(env.get(ClientEnvs.REMOTE_SERVER_URL)),
                syncInterval,
                clientIdConfigured,
                clientIdConfigured ? env.get(ClientEnvs.CLIENT_ID) : null,
                optional(env, ClientEnvs.AUTH_SERVER_URL),
                optional(env, ClientEnvs.CLIENT_SECRET),
                optional(env, ClientEnvs.CLIENT_TOKEN));
    }

    private static Duration syncInterval(long minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES.name()
                    + " must be at least 1.");
        }
        return Duration.ofMinutes(minutes);
    }

    private static Path deadLetterPath(Path offlineLog) {
        String fileName = offlineLog.getFileName() == null ? "offline" : offlineLog.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String deadLetterName = extension > 0
                ? fileName.substring(0, extension) + "-dead-letter" + fileName.substring(extension)
                : fileName + "-dead-letter.json";
        return offlineLog.toAbsolutePath().normalize().resolveSibling(deadLetterName);
    }

    private static String optional(Env env, EnvOption<String> option) {
        return env.has(option) ? env.get(option) : null;
    }
}

package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.env.Env;

import java.nio.file.Files;
import java.nio.file.Path;

public final class OfflineFileLockerFactory {
    private OfflineFileLockerFactory() {
    }

    public static OfflineFileLockerClient create(Env env) {
        if (env.has(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET)) {
            return new OfflineFileLockerClient(Path.of(env.get(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET)));
        }
        return new OfflineFileLockerClient(defaultSocket());
    }

    /**
     * The current socket, or the pre-rename one when only that exists — which is the case when the
     * file-locker service has not been restarted since the rename.
     */
    private static Path defaultSocket() {
        Path current = OfflineFileLockerClient.DEFAULT_SOCKET_PATH;
        if (Files.exists(current) || !Files.exists(OfflineFileLockerClient.LEGACY_SOCKET_PATH)) {
            return current;
        }
        System.err.printf("Using the pre-rename file-locker socket %s; restart the file-locker "
                        + "service to move to %s.%n",
                OfflineFileLockerClient.LEGACY_SOCKET_PATH, current);
        return OfflineFileLockerClient.LEGACY_SOCKET_PATH;
    }
}

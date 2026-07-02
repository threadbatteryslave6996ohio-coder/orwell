package dev.clippy.clients.core;

import dev.clippy.clients.core.env.ClientEnvs;
import dev.clippy.clients.filelocker.OfflineFileLockerClient;
import dev.clippy.utils.envmanager.Env;

import java.nio.file.Path;

public final class OfflineFileLockerFactory {
    private OfflineFileLockerFactory() {
    }

    public static OfflineFileLockerClient create(Env env) {
        Path fileLockerSocket = env.has(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET)
                ? Path.of(env.get(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET))
                : OfflineFileLockerClient.DEFAULT_SOCKET_PATH;
        return new OfflineFileLockerClient(fileLockerSocket);
    }
}

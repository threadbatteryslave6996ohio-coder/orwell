package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.env.Env;

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

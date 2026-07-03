package dev.clippy.clients.core.env;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvFiles;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSnapshotLogger;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.io.IOException;
import java.util.Map;

public final class ClientEnvs {
    public static final EnvOption<String> REMOTE_SERVER_URL;
    public static final EnvOption<String> CLIENT_ID;
    public static final EnvOption<String> CLIENT_TOKEN;
    public static final EnvOption<String> CLIENT_SECRET;
    public static final EnvOption<String> AUTH_SERVER_URL;
    public static final EnvOption<Long> CLIPBOARD_POLL_INTERVAL_MS;
    public static final EnvOption<String> CLIPBOARD_BACKEND;
    public static final EnvOption<String> OFFLINE_FILE_LOCKER_SOCKET;
    public static final EnvOption<Long> OFFLINE_SYNC_INTERVAL_MINUTES;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        REMOTE_SERVER_URL = builder.required("REMOTE_SERVER_URL", EnvType.string());
        CLIENT_ID = builder.optional("CLIENT_ID", EnvType.string());
        CLIENT_TOKEN = builder.optional("CLIENT_TOKEN", EnvType.string());
        CLIENT_SECRET = builder.optional("CLIENT_SECRET", EnvType.string());
        AUTH_SERVER_URL = builder.optional("AUTH_SERVER_URL", EnvType.string());
        CLIPBOARD_POLL_INTERVAL_MS = builder.optional("CLIPBOARD_POLL_INTERVAL_MS", EnvType.longInteger());
        CLIPBOARD_BACKEND = builder.optional("CLIPBOARD_BACKEND", EnvType.string());
        OFFLINE_FILE_LOCKER_SOCKET = builder.optional("OFFLINE_FILE_LOCKER_SOCKET", EnvType.string());
        OFFLINE_SYNC_INTERVAL_MINUTES = builder.optional("OFFLINE_SYNC_INTERVAL_MINUTES", EnvType.longInteger());
        ENV = builder.build();
    }

    private ClientEnvs() {
    }

    public static Env fromSystem() {
        return ENV.fromSystem();
    }

    public static Env load() throws IOException {
        return from(EnvFiles.load());
    }

    public static Env load(EnvSnapshotLogger logger) throws IOException {
        return from(EnvFiles.load(), logger);
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Env from(Map<String, String> source, EnvSnapshotLogger logger) {
        return logger == null ? ENV.from(source) : ENV.from(source, logger);
    }
}

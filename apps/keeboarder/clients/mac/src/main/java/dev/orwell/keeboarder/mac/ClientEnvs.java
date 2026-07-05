package dev.orwell.keeboarder.mac;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.net.InetAddress;
import java.util.Map;

public final class ClientEnvs {
    public static final EnvOption<String> KEEBOARDER_SERVER_URL;
    public static final EnvOption<String> KEEBOARDER_AUTH_BASE_URL;
    public static final EnvOption<String> KEEBOARDER_CLIENT_NAME;
    public static final EnvOption<String> KEEBOARDER_CLIENT_ID;
    public static final EnvOption<String> KEEBOARDER_CLIENT_SECRET;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        KEEBOARDER_SERVER_URL = builder.optional("KEEBOARDER_SERVER_URL", EnvType.string(), "ws://localhost:8025/ws/chat");
        KEEBOARDER_AUTH_BASE_URL = builder.optional("KEEBOARDER_AUTH_BASE_URL", EnvType.string(), "http://localhost:8081");
        KEEBOARDER_CLIENT_NAME = builder.optional("KEEBOARDER_CLIENT_NAME", EnvType.string(), "");
        KEEBOARDER_CLIENT_ID = builder.required("KEEBOARDER_CLIENT_ID", EnvType.string());
        KEEBOARDER_CLIENT_SECRET = builder.required("KEEBOARDER_CLIENT_SECRET", EnvType.string());
        ENV = builder.build();
    }

    private ClientEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    static String defaultName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host == null || host.isBlank()) {
                return "MacClient";
            }
            return "Mac-" + host;
        } catch (Exception exception) {
            return "MacClient";
        }
    }
}

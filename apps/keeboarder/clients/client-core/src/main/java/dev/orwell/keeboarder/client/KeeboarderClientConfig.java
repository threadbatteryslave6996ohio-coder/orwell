package dev.orwell.keeboarder.client;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.net.InetAddress;
import java.util.Map;

public record KeeboarderClientConfig(String serverUrl, String authBaseUrl, String name, String clientId, String clientSecret) {
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

    public static KeeboarderClientConfig fromEnv(Map<String, String> rawEnv, String defaultName) {
        Env env = ENV.from(rawEnv);
        String serverUrl = env.get(KEEBOARDER_SERVER_URL);
        String authBaseUrl = env.get(KEEBOARDER_AUTH_BASE_URL);
        String name = env.get(KEEBOARDER_CLIENT_NAME);
        if (name.isBlank()) {
            name = defaultName;
        }
        String clientId = env.get(KEEBOARDER_CLIENT_ID);
        String clientSecret = env.get(KEEBOARDER_CLIENT_SECRET);
        return new KeeboarderClientConfig(serverUrl, authBaseUrl, name, clientId, clientSecret);
    }

    public static String defaultName(String prefix, String fallback) {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host == null || host.isBlank()) {
                return fallback;
            }
            return prefix + host;
        } catch (Exception exception) {
            return fallback;
        }
    }
}

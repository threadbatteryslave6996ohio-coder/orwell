package dev.orwell.keeboarder.server.config;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class KeeboarderEnvs {
    public static final EnvOption<String> HTTP_HOST;
    public static final EnvOption<String> HTTP_PORT;
    public static final EnvOption<String> WEBSOCKET_HOST;
    public static final EnvOption<String> WEBSOCKET_PORT;
    public static final EnvOption<String> WEBSOCKET_CONTEXT_PATH;
    public static final EnvOption<Boolean> WEBSOCKET_ENABLED;
    public static final EnvOption<String> REDIS_HOST;
    public static final EnvOption<String> REDIS_PORT;
    public static final EnvOption<String> CLIPPY_AUTH_BASE_URL;
    public static final EnvOption<String> KEEBOARDER_SERVER_ROUTE_PREFIX;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        HTTP_HOST = builder.optional("HTTP_HOST", EnvType.string(), "0.0.0.0");
        HTTP_PORT = builder.optional("HTTP_PORT", EnvType.string(), "8080");
        WEBSOCKET_HOST = builder.optional("WEBSOCKET_HOST", EnvType.string(), "0.0.0.0");
        WEBSOCKET_PORT = builder.optional("WEBSOCKET_PORT", EnvType.string(), "8025");
        WEBSOCKET_CONTEXT_PATH = builder.optional("WEBSOCKET_CONTEXT_PATH", EnvType.string(), "/ws");
        WEBSOCKET_ENABLED = builder.optional("WEBSOCKET_ENABLED", EnvType.bool(), true);
        REDIS_HOST = builder.optional("REDIS_HOST", EnvType.string(), "localhost");
        REDIS_PORT = builder.optional("REDIS_PORT", EnvType.string(), "6379");
        CLIPPY_AUTH_BASE_URL = builder.optional("CLIPPY_AUTH_BASE_URL", EnvType.string(), "http://localhost:8081");
        KEEBOARDER_SERVER_ROUTE_PREFIX = builder.optional("KEEBOARDER_SERVER_ROUTE_PREFIX", EnvType.string(), "/api");
        ENV = builder.build();
    }

    private KeeboarderEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("server.address", env.get(HTTP_HOST));
        values.put("server.port", env.get(HTTP_PORT));
        values.put("keeboarder.server.route-prefix", env.get(KEEBOARDER_SERVER_ROUTE_PREFIX));
        values.put("keeboarder.websocket.host", env.get(WEBSOCKET_HOST));
        values.put("keeboarder.websocket.port", env.get(WEBSOCKET_PORT));
        values.put("keeboarder.websocket.context-path", env.get(WEBSOCKET_CONTEXT_PATH));
        values.put("keeboarder.websocket.enabled", env.get(WEBSOCKET_ENABLED));
        values.put("keeboarder.redis.host", env.get(REDIS_HOST));
        values.put("keeboarder.redis.port", env.get(REDIS_PORT));
        values.put("clippy.auth.base-url", env.get(CLIPPY_AUTH_BASE_URL));
        return values;
    }
}

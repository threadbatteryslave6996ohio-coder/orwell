package dev.orwell.keeboarder.server.config;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class KeeboarderEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);
    public static final EnvOption<String> WEBSOCKET_CONTEXT_PATH;
    public static final EnvOption<Boolean> WEBSOCKET_ENABLED;
    public static final EnvOption<String> REDIS_HOST;
    public static final EnvOption<String> REDIS_PORT;
    public static final EnvOption<String> KEEBOARDER_SERVER_ROUTE_PREFIX;

    static {
        WEBSOCKET_CONTEXT_PATH = ENV.optional("WEBSOCKET_CONTEXT_PATH", EnvType.string(), "/ws");
        WEBSOCKET_ENABLED = ENV.optional("WEBSOCKET_ENABLED", EnvType.bool(), true);
        REDIS_HOST = ENV.optional("REDIS_HOST", EnvType.string(), "localhost");
        REDIS_PORT = ENV.optional("REDIS_PORT", EnvType.string(), "6379");
        KEEBOARDER_SERVER_ROUTE_PREFIX = ENV.optional("KEEBOARDER_SERVER_ROUTE_PREFIX", EnvType.string(), "/api");
        ENV.property("keeboarder.server.route-prefix", KEEBOARDER_SERVER_ROUTE_PREFIX);
        ENV.property("keeboarder.websocket.context-path", WEBSOCKET_CONTEXT_PATH);
        ENV.property("keeboarder.websocket.enabled", WEBSOCKET_ENABLED);
        ENV.property("keeboarder.redis.host", REDIS_HOST);
        ENV.property("keeboarder.redis.port", REDIS_PORT);
    }

    private KeeboarderEnvs() {
    }
}

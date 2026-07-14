package dev.orwell.keeboarder.server.websocket;

import dev.orwell.auth.AuthenticationStrategy;

/**
 * Owns the Redis-backed client cache and wires it (with the authenticator) into {@link ChatEndpoint}
 * for the lifetime of the Spring context. The WebSocket transport itself is now served by Spring
 * Boot's embedded container via {@code ServerEndpointExporter}; this type only manages the shared
 * resources the endpoint depends on.
 */
public final class KeeboarderWebSocketRuntime {
    private final boolean enabled;
    private final String redisHost;
    private final int redisPort;
    private final AuthenticationStrategy authenticator;

    private RedisClientCache cache;

    public KeeboarderWebSocketRuntime(
            boolean enabled,
            String redisHost,
            int redisPort,
            AuthenticationStrategy authenticator
    ) {
        this.enabled = enabled;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.authenticator = authenticator;
    }

    public synchronized void start() {
        if (!enabled) {
            return;
        }
        cache = new RedisClientCache(redisHost, redisPort);
        ChatEndpoint.initialize(cache, authenticator);
    }

    public synchronized void stop() {
        if (cache != null) {
            cache.close();
            cache = null;
        }
        ChatEndpoint.initialize(null, null);
    }
}

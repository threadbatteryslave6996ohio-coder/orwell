package dev.orwell.keeboarder.server;

import dev.orwell.auth.AuthenticationStrategy;
import org.glassfish.tyrus.server.Server;
import org.springframework.context.SmartLifecycle;

/** Owns the WebSocket and Redis resources when Keeboarder runs inside Spring. */
public final class KeeboarderWebSocketServer implements SmartLifecycle {
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String contextPath;
    private final String redisHost;
    private final int redisPort;
    private final AuthenticationStrategy authenticator;

    private volatile boolean running;
    private RedisClientCache cache;
    private Server server;

    public KeeboarderWebSocketServer(
            boolean enabled,
            String host,
            int port,
            String contextPath,
            String redisHost,
            int redisPort,
            AuthenticationStrategy authenticator
    ) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.contextPath = contextPath;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.authenticator = authenticator;
    }

    @Override
    public synchronized void start() {
        if (!enabled || running) {
            return;
        }

        cache = new RedisClientCache(redisHost, redisPort);
        server = new Server(host, port, contextPath, null, ChatEndpoint.class);
        ChatEndpoint.initialize(cache, authenticator);
        try {
            server.start();
            running = true;
        } catch (Exception exception) {
            releaseResources();
            throw new IllegalStateException(
                    "Could not start Keeboarder WebSocket server on " + host + ":" + port, exception);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        releaseResources();
        running = false;
    }

    private void releaseResources() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (cache != null) {
            cache.close();
            cache = null;
        }
        ChatEndpoint.initialize(null, null);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}

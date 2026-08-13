package dev.orwell.keeboarder.server.websocket;

import dev.orwell.redis.RedisClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who is connected, kept in the shared Redis so the answer survives a restart and is the same for
 * every server instance.
 *
 * <p>The Redis mechanics — pooling, key namespacing, turning a driver failure into one exception
 * type — live in {@link RedisClient}. What is left here is the shape of the data: a hash per client
 * under {@code client:<id>}, and a set of live ids under {@code clients}, both inside keeboarder's
 * {@value #KEY_PREFIX} namespace.
 *
 * <p>Failures propagate as {@link dev.orwell.redis.RedisOperationException}, which is a
 * {@link RuntimeException} — {@code ChatEndpoint} catches it and tells the client its registration
 * did not happen, rather than reporting a success Redis never recorded.
 */
public class RedisClientCache {
    /** Owned by keeboarder-server. Anything else in the shared Redis stays out of it. */
    static final String KEY_PREFIX = "ws:";

    private static final String CLIENT_SET_KEY = "clients";

    private final RedisClient redis;

    public RedisClientCache(String host, int port) {
        this(new RedisClient(host, port, KEY_PREFIX));
    }

    public RedisClientCache(RedisClient redis) {
        this.redis = redis;
    }

    public void registerClient(String clientId, String name, String connectedAt) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("name", name);
        metadata.put("connectedAt", connectedAt);
        redis.putHash(getClientKey(clientId), metadata);
        redis.addToSet(CLIENT_SET_KEY, clientId);
    }

    public void unregisterClient(String clientId) {
        redis.removeFromSet(CLIENT_SET_KEY, clientId);
        redis.delete(getClientKey(clientId));
    }

    public Optional<ClientInfo> getClientInfo(String clientId) {
        Map<String, String> metadata = redis.getHash(getClientKey(clientId));
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ClientInfo(clientId, metadata.get("name"), metadata.get("connectedAt")));
    }

    public Set<String> getAllClientIds() {
        return redis.getSetMembers(CLIENT_SET_KEY);
    }

    public void close() {
        redis.close();
    }

    private static String getClientKey(String clientId) {
        return "client:" + clientId;
    }

    public static class ClientInfo {
        public final String clientId;
        public final String name;
        public final String connectedAt;

        public ClientInfo(String clientId, String name, String connectedAt) {
            this.clientId = clientId;
            this.name = name;
            this.connectedAt = connectedAt;
        }
    }
}

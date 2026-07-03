package com.keeboarder.server;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RedisClientCache {
    private static final String CLIENT_SET_KEY = "ws:clients";
    private final JedisPool jedisPool;

    public RedisClientCache(String host, int port) {
        this.jedisPool = new JedisPool(host, port);
    }

    public void registerClient(String clientId, String name, String connectedAt) {
        try (Jedis jedis = jedisPool.getResource()) {
            String clientKey = getClientKey(clientId);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("name", name);
            metadata.put("connectedAt", connectedAt);
            jedis.hset(clientKey, metadata);
            jedis.sadd(CLIENT_SET_KEY, clientId);
        }
    }

    public void unregisterClient(String clientId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.srem(CLIENT_SET_KEY, clientId);
            jedis.del(getClientKey(clientId));
        }
    }

    public Optional<ClientInfo> getClientInfo(String clientId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> metadata = jedis.hgetAll(getClientKey(clientId));
            if (metadata == null || metadata.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ClientInfo(clientId, metadata.get("name"), metadata.get("connectedAt")));
        }
    }

    public Set<String> getAllClientIds() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.smembers(CLIENT_SET_KEY);
        }
    }

    public Optional<String> findClientIdByName(String name) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> clientIds = jedis.smembers(CLIENT_SET_KEY);
            for (String clientId : clientIds) {
                String storedName = jedis.hget(getClientKey(clientId), "name");
                if (name.equals(storedName)) {
                    return Optional.of(clientId);
                }
            }
            return Optional.empty();
        }
    }

    public void close() {
        jedisPool.close();
    }

    private static String getClientKey(String clientId) {
        return "ws:client:" + clientId;
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

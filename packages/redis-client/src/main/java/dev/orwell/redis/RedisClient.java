package dev.orwell.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A pooled, namespaced handle on the one shared Redis.
 *
 * <p>There is exactly one Redis in this repo (the {@code redis} service in
 * {@code docker-compose.all-services.yml}), so every app in it is a guest in the same keyspace.
 * This client makes that safe by construction: it takes a <b>prefix</b> and puts it in front of
 * every key it touches, so {@code ws:} and {@code insta:} can never collide no matter what key a
 * caller passes. Redis's own answer to "workspaces" is numbered logical databases, and they are
 * the wrong tool — they share one memory pool, one persistence file and one eviction policy,
 * {@code FLUSHALL} crosses all of them, and Redis Cluster supports only database 0. So this client
 * does not offer one: everything lives in database 0, and the prefix does the separating. A caller
 * that truly needs another database can hand in its own {@link JedisPool}.
 *
 * <p><b>Failure is the caller's call.</b> Every command is wrapped, and a Jedis failure becomes a
 * {@link RedisOperationException} naming the command and the namespaced key. This client
 * deliberately does not decide whether that is fatal: a cache answers a failed read with a miss
 * and pays for the work again, while a client registry has to tell the caller its registration did
 * not happen. Both are right, and neither can be imposed from here.
 *
 * <p><b>Connections are borrowed per command.</b> Each call takes a connection from the pool and
 * returns it, so instances are thread-safe and cheap to share. A caller that issues many commands
 * in a loop pays one pool checkout each; that is a queue pop, not a new socket.
 *
 * <p>Construction opens no connection — the pool is lazy — so an instance can be built before the
 * shared Redis is up.
 */
public class RedisClient implements AutoCloseable {

    /** Long enough to survive a busy Redis, short enough that a dead one fails a request fast. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private final JedisPool pool;
    private final String prefix;

    /**
     * @param prefix the namespace this client owns, e.g. {@code "ws:"}. A trailing {@code ':'} is
     *               added when missing. Must not be blank — the prefix is the only thing keeping
     *               two apps out of each other's keys.
     */
    public RedisClient(String host, int port, String prefix) {
        this(host, port, DEFAULT_CONNECT_TIMEOUT, prefix);
    }

    public RedisClient(String host, int port, Duration connectTimeout, String prefix) {
        this(
                new JedisPool(
                        new JedisPoolConfig(),
                        Objects.requireNonNull(host, "host"),
                        port,
                        (int) Objects.requireNonNull(connectTimeout, "connectTimeout").toMillis(),
                        null),
                prefix);
    }

    /** For callers that need to configure the pool themselves; the client takes ownership of it. */
    public RedisClient(JedisPool pool, String prefix) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.prefix = normalizePrefix(prefix);
    }

    /** The namespace every key of this client lives under, trailing {@code ':'} included. */
    public String prefix() {
        return prefix;
    }

    /** The physical Redis key {@code key} maps to. Useful in tests and in log metadata. */
    public String namespaced(String key) {
        return prefix + Objects.requireNonNull(key, "key");
    }

    /** @return the stored string, or empty when the key is not set. */
    public Optional<String> get(String key) {
        return run("GET", key, jedis -> Optional.ofNullable(jedis.get(namespaced(key))));
    }

    /** Stores {@code value} with no expiry. */
    public void set(String key, String value) {
        run("SET", key, jedis -> jedis.set(namespaced(key), value));
    }

    /** Stores {@code value} with an expiry; {@code ttl} is rounded up to a whole second. */
    public void set(String key, String value, Duration ttl) {
        long seconds = Math.max(Objects.requireNonNull(ttl, "ttl").toSeconds(), 1);
        run("SETEX", key, jedis -> jedis.setex(namespaced(key), seconds, value));
    }

    /** Removes the key. Removing one that is not there is not an error. */
    public void delete(String key) {
        run("DEL", key, jedis -> jedis.del(namespaced(key)));
    }

    /** Writes the given fields into the hash at {@code key}, leaving any others in place. */
    public void putHash(String key, Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        run("HSET", key, jedis -> jedis.hset(namespaced(key), fields));
    }

    /** @return every field of the hash, or an empty map when the key is not set. */
    public Map<String, String> getHash(String key) {
        return run("HGETALL", key, jedis -> {
            Map<String, String> fields = jedis.hgetAll(namespaced(key));
            return fields == null ? Map.of() : fields;
        });
    }

    /** @return one field of the hash, or empty when either the key or the field is missing. */
    public Optional<String> getHashField(String key, String field) {
        Objects.requireNonNull(field, "field");
        return run("HGET", key, jedis -> Optional.ofNullable(jedis.hget(namespaced(key), field)));
    }

    /** Adds a member to the set at {@code key}. Adding one twice is not an error. */
    public void addToSet(String key, String member) {
        run("SADD", key, jedis -> jedis.sadd(namespaced(key), member));
    }

    /** Removes a member from the set at {@code key}. Removing an absent one is not an error. */
    public void removeFromSet(String key, String member) {
        run("SREM", key, jedis -> jedis.srem(namespaced(key), member));
    }

    /** @return every member of the set, or an empty set when the key is not set. */
    public Set<String> getSetMembers(String key) {
        return run("SMEMBERS", key, jedis -> {
            Set<String> members = jedis.smembers(namespaced(key));
            return members == null ? Set.of() : members;
        });
    }

    /** Closes the pool. Commands issued afterwards fail with {@link RedisOperationException}. */
    @Override
    public void close() {
        pool.close();
    }

    private <T> T run(String command, String key, Function<Jedis, T> work) {
        try (Jedis jedis = pool.getResource()) {
            return work.apply(jedis);
        } catch (JedisException exception) {
            throw new RedisOperationException(command, namespaced(key), exception);
        }
    }

    private static String normalizePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        String trimmed = prefix.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "A Redis key prefix is required: the shared Redis is one keyspace, and the "
                            + "prefix is what keeps this app's keys out of everyone else's.");
        }
        return trimmed.endsWith(":") ? trimmed : trimmed + ":";
    }
}

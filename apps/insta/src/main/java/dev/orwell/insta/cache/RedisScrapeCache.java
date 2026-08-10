package dev.orwell.insta.cache;

import dev.orwell.logging.Logger;
import dev.orwell.redis.RedisClient;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ScrapeCache} on the shared Redis.
 *
 * <p><b>Namespacing.</b> There is exactly one Redis in this repo (the {@code redis} service in
 * {@code docker-compose.all-services.yml}) and keeboarder-server is already in it under
 * {@code ws:}. Redis's own answer to "workspaces" is numbered logical databases, and they are the
 * wrong tool here: they share one memory pool, one persistence file and one eviction policy,
 * {@code FLUSHALL} still wipes every one of them, and Redis Cluster supports only database 0 — so
 * choosing a database now would quietly foreclose ever clustering. This cache therefore does what
 * keeboarder does and hands {@link RedisClient} the {@value #KEY_PREFIX} prefix, which it puts in
 * front of every key. Everything lives in database 0; there is no knob for another one, because
 * the prefix is the separation.
 *
 * <p><b>Entries do not expire.</b> There is no time to live at all: the point of one was that
 * Instagram data goes stale, but actor quotas turned out to bind long before freshness does, so a
 * cached answer stays authoritative until a later walk overwrites it. Re-add expiry by giving
 * {@link RedisClient#set(String, String, java.time.Duration)} a duration here.
 *
 * <p><b>Failure.</b> {@link RedisClient} reports a failure and leaves the policy here, which is
 * the point: a miss and an unreachable Redis are the same answer to a caller — one Apify run —
 * which is the right trade, because a cache outage should cost money, not availability.
 */
public class RedisScrapeCache implements ScrapeCache, AutoCloseable {
    /** Owned by this cache. Anything else in the shared Redis stays out of this namespace. */
    static final String KEY_PREFIX = "insta:";

    private final RedisClient redis;
    private final Logger logger;

    public RedisScrapeCache(String host, int port, Logger logger) {
        this(new RedisClient(host, port, KEY_PREFIX), logger);
    }

    public RedisScrapeCache(RedisClient redis, Logger logger) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public Optional<String> find(String key) {
        try {
            return redis.get(key);
        } catch (Exception exception) {
            logger.warn("Could not read the Instagram scrape cache; treating it as a miss.",
                    Map.of("key", key, "error", exception.toString()));
            return Optional.empty();
        }
    }

    @Override
    public void store(String key, String json) {
        try {
            redis.set(key, json);
        } catch (Exception exception) {
            // The answer is already on its way to the caller; failing to remember it changes
            // nothing for this request and must not turn a success into a 500.
            logger.warn("Could not write to the Instagram scrape cache.",
                    Map.of("key", key, "error", exception.toString()));
        }
    }

    @Override
    public void close() {
        redis.close();
    }
}

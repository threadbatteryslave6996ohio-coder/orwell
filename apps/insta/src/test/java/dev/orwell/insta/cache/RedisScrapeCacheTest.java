package dev.orwell.insta.cache;

import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The Redis half of the cache, against a real Redis: the key namespace it writes into, the expiry
 * it sets, and what it does when Redis is not there.
 *
 * <p>This uses an ephemeral Testcontainers Redis on a random port — the repo's one-Redis rule is
 * about long-lived instances, and a test must not depend on the shared stack being up.
 */
@Testcontainers
class RedisScrapeCacheTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private final List<RedisScrapeCache> opened = new ArrayList<>();

    @AfterEach
    void closeCaches() {
        opened.forEach(RedisScrapeCache::close);
        opened.clear();
        try (Jedis jedis = jedis()) {
            jedis.flushAll();
        }
    }

    @Test
    void storesAndReadsBackAValue() {
        ScrapeCache cache = cache(Duration.ofHours(24));

        cache.store("v1:profile:nasa", "{\"username\":\"nasa\"}");

        assertThat(cache.find("v1:profile:nasa")).contains("{\"username\":\"nasa\"}");
    }

    @Test
    void missesOnAKeyItNeverWrote() {
        assertThat(cache(Duration.ofHours(24)).find("v1:profile:nobody")).isEmpty();
    }

    /**
     * The namespace is the whole answer to sharing one Redis with keeboarder-server, which owns
     * {@code ws:}. If this prefix ever silently disappeared the two would be free to collide.
     */
    @Test
    void writesEveryKeyUnderItsOwnPrefix() {
        cache(Duration.ofHours(24)).store("v1:profile:nasa", "{}");

        try (Jedis jedis = jedis()) {
            assertThat(jedis.keys("*")).containsExactly(RedisScrapeCache.KEY_PREFIX + "v1:profile:nasa");
            assertThat(jedis.exists("v1:profile:nasa")).isFalse();
        }
    }

    /** Instagram data goes stale, and an entry with no expiry would outlive its usefulness. */
    @Test
    void expiresEntriesAfterTheConfiguredTimeToLive() {
        cache(Duration.ofHours(24)).store("v1:profile:nasa", "{}");

        try (Jedis jedis = jedis()) {
            long ttl = jedis.ttl(RedisScrapeCache.KEY_PREFIX + "v1:profile:nasa");
            assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofHours(24).toSeconds());
        }
    }

    /** A cache outage must cost an Apify run, never a request. */
    @Test
    void reportsAMissAndSwallowsWritesWhenRedisIsUnreachable() {
        // Port 1 is reserved and refuses connections, standing in for Redis being down.
        RedisScrapeCache cache =
                new RedisScrapeCache("127.0.0.1", 1, Duration.ofHours(24), NO_OP_LOGGER);
        opened.add(cache);

        assertThatCode(() -> cache.store("v1:profile:nasa", "{}")).doesNotThrowAnyException();
        assertThat(cache.find("v1:profile:nasa")).isEmpty();
    }

    private ScrapeCache cache(Duration ttl) {
        RedisScrapeCache cache = new RedisScrapeCache(
                REDIS.getHost(), REDIS.getMappedPort(6379), ttl, NO_OP_LOGGER);
        opened.add(cache);
        return cache;
    }

    private static Jedis jedis() {
        return new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379));
    }
}

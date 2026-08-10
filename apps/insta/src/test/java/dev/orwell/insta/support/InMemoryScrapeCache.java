package dev.orwell.insta.support;

import dev.orwell.insta.cache.ScrapeCache;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ScrapeCache} the service tests can see into. Caching behaviour is about which requests
 * reach Apify and which do not, so what matters here is the key that was written — not the TTL,
 * which is Redis's job and is covered by {@code RedisScrapeCacheTest}.
 */
public final class InMemoryScrapeCache implements ScrapeCache {
    private final Map<String, String> entries = new ConcurrentHashMap<>();
    private volatile boolean broken;

    @Override
    public Optional<String> find(String key) {
        if (broken) {
            // What the Redis cache does when it cannot be reached: report a miss, never throw.
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void store(String key, String json) {
        if (broken) {
            return;
        }
        entries.put(key, json);
    }

    /** Makes every read a miss and drops every write, as an unreachable Redis would. */
    public void breakIt() {
        this.broken = true;
    }

    public Map<String, String> entries() {
        return entries;
    }
}

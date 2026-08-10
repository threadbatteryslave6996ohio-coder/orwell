package dev.orwell.insta.cache;

import java.util.Optional;

/**
 * The cache you get when {@code INSTA_CACHE_ENABLED=false}: every lookup misses and every store is
 * dropped. Exists so the service has one code path whether or not a Redis is around — a null cache
 * would put a branch at every call site instead.
 */
public class DisabledScrapeCache implements ScrapeCache {

    @Override
    public Optional<String> find(String key) {
        return Optional.empty();
    }

    @Override
    public void store(String key, String json) {
    }
}

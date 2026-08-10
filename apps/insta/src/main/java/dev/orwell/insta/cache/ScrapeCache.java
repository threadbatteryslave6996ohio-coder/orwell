package dev.orwell.insta.cache;

import java.util.Optional;

/**
 * Remembers what an Apify run already told us, so asking again is free.
 *
 * <p>Every miss costs real money — the connections actor bills per account returned — which sets
 * the two rules every implementation follows. First, entries expire: Instagram data goes stale and
 * a 24-hour-old follower list is still worth more than a fresh charge. Second, **a cache failure is
 * never a request failure**: if the store is down or slow, a miss is the correct answer and the
 * caller pays for a scrape, rather than getting a 500 because a cache was unreachable.
 *
 * <p>Values are opaque JSON strings. Keeping serialization in the caller means the cache does not
 * need to know about profiles, pages, or Jackson.
 */
public interface ScrapeCache extends AutoCloseable {

    /** @return the stored JSON, or empty on a miss — including when the store is unreachable. */
    Optional<String> find(String key);

    /** Stores {@code json} under {@code key}. Entries do not expire. Never throws. */
    void store(String key, String json);

    /**
     * Releases whatever the implementation holds. Defaulted to nothing so an in-memory or disabled
     * cache costs a caller no ceremony, and overridden with no checked exception so a
     * try-with-resources around a lookup does not have to catch one.
     */
    @Override
    default void close() {
    }
}

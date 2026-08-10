package dev.orwell.redis;

/**
 * Thrown when a Redis command fails — the store is unreachable, the pool is exhausted, or Redis
 * itself answered with an error.
 *
 * <p>Unchecked on purpose. {@link RedisClient} does not decide what a Redis outage means; its
 * callers do, and they disagree for good reasons. A cache treats a failure as a miss and pays for
 * the work again; a client registry treats it as a failed registration and tells the caller.
 * Wrapping every failure in one unchecked type lets each of them catch exactly as much as it wants
 * without the client having to guess.
 */
public class RedisOperationException extends RuntimeException {

    public RedisOperationException(String command, String key, Throwable cause) {
        super("Redis " + command + " failed for key '" + key + "'.", cause);
    }
}

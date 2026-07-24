package dev.orwell.liveness;

import java.util.HashMap;
import java.util.Map;

/**
 * Gates repeat alerts for the same key (here, a client id) so a client that stays down does not
 * produce one alert every check interval. Mirrors the log-analyzer tracker, plus a {@link #clear}
 * used when a client recovers so its next outage alerts immediately rather than waiting out a
 * cooldown started by the previous one.
 */
final class AlertCooldownTracker {
    private final int cooldownSeconds;
    private final Map<String, Long> lastAlertByKey = new HashMap<>();

    AlertCooldownTracker(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    /**
     * Atomically checks the cooldown and, if it has elapsed, reserves the slot by recording
     * {@code nowSeconds}. Returns true only for the caller that wins the slot. A winner that does
     * not actually deliver its alert must call {@link #rollback} so the outage can re-alert before
     * the cooldown elapses.
     */
    synchronized boolean tryAcquire(String key, long nowSeconds) {
        long last = lastAlertByKey.getOrDefault(key, 0L);
        if (last != 0L && nowSeconds - last < cooldownSeconds) {
            return false;
        }
        lastAlertByKey.put(key, nowSeconds);
        return true;
    }

    /** Releases a reservation only if a newer one has not replaced it. */
    synchronized void rollback(String key, long reservedSeconds) {
        if (lastAlertByKey.getOrDefault(key, Long.MIN_VALUE) == reservedSeconds) {
            lastAlertByKey.remove(key);
        }
    }

    /** Forgets a key entirely, so the next {@link #tryAcquire} for it wins immediately. */
    synchronized void clear(String key) {
        lastAlertByKey.remove(key);
    }
}

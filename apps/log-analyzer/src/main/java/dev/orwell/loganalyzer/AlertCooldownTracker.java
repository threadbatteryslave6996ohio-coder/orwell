package dev.orwell.loganalyzer;

import java.util.HashMap;
import java.util.Map;

final class AlertCooldownTracker {
    private final int cooldownSeconds;
    private final Map<String, Long> lastAlertByFingerprint = new HashMap<>();

    AlertCooldownTracker(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    /**
     * Atomically checks the cooldown and, if it has elapsed, reserves the slot by
     * recording {@code nowSeconds}. Returns true only for the caller that wins the
     * slot, so two concurrent polls for the same fingerprint cannot both proceed
     * (the check and the record happen under one lock and cannot interleave). A
     * winner that does not actually deliver its alert must call {@link #rollback}
     * so the anomaly can be retried before the cooldown elapses.
     */
    synchronized boolean tryAcquire(String fingerprint, long nowSeconds) {
        // Drop entries whose cooldown has fully elapsed so the map cannot grow without
        // bound in a long-running process as anomaly fingerprints churn. An elapsed entry
        // would allow a fresh acquire anyway, so removing it is behaviour-preserving.
        evictExpired(nowSeconds);
        long last = lastAlertByFingerprint.getOrDefault(fingerprint, 0L);
        if (nowSeconds - last < cooldownSeconds) {
            return false;
        }
        lastAlertByFingerprint.put(fingerprint, nowSeconds);
        return true;
    }

    private void evictExpired(long nowSeconds) {
        lastAlertByFingerprint.values().removeIf(reserved -> nowSeconds - reserved >= cooldownSeconds);
    }

    /**
     * Releases a reservation from {@link #tryAcquire} when the alert was not
     * delivered, but only if no newer reservation has replaced it (so a slow
     * caller cannot clobber a later successful alert for the same fingerprint).
     */
    synchronized void rollback(String fingerprint, long reservedSeconds) {
        if (lastAlertByFingerprint.getOrDefault(fingerprint, Long.MIN_VALUE) == reservedSeconds) {
            lastAlertByFingerprint.remove(fingerprint);
        }
    }
}

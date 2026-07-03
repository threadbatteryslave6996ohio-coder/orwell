package dev.clippy.bucket.detection;

import java.util.HashMap;
import java.util.Map;

final class CooldownTracker {
    private final int cooldownSeconds;
    private final Map<String, Long> lastAlertBySource = new HashMap<>();

    CooldownTracker(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    synchronized boolean allow(String source, long nowSeconds) {
        long last = lastAlertBySource.getOrDefault(source, 0L);
        if (nowSeconds - last < cooldownSeconds) {
            return false;
        }
        lastAlertBySource.put(source, nowSeconds);
        return true;
    }
}

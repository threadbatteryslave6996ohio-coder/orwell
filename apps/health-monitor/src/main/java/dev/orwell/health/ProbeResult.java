package dev.orwell.health;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one check found.
 *
 * @param healthy      whether this counts as up. See {@link HealthProbe} for what that means.
 * @param httpStatus   the status code, or 0 when nothing answered at all.
 * @param reportedStatus the {@code status} field from the body, when there was one.
 * @param durationMillis how long the request took, measured around the send.
 * @param detail       why it is down, or null when it is up. Goes in the alert and the log, so it is
 *                     phrased to be read by somebody who has just been woken up.
 */
public record ProbeResult(
        boolean healthy,
        int httpStatus,
        String reportedStatus,
        long durationMillis,
        String detail) {

    static ProbeResult up(int httpStatus, String reportedStatus, long durationMillis) {
        return new ProbeResult(true, httpStatus, reportedStatus, durationMillis, null);
    }

    static ProbeResult down(int httpStatus, String reportedStatus, long durationMillis,
            String detail) {
        return new ProbeResult(false, httpStatus, reportedStatus, durationMillis, detail);
    }

    public Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("healthy", healthy);
        json.put("httpStatus", httpStatus);
        json.put("reportedStatus", reportedStatus);
        json.put("durationMillis", durationMillis);
        json.put("detail", detail);
        return json;
    }
}

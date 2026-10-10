package dev.orwell.liveness;

import dev.orwell.bootstrap.schedule.ScheduledJob;
import dev.orwell.bootstrap.web.SharedJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.Logger;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Polls Loki for client heartbeats on a schedule and decides, per client, whether it has gone
 * silent longer than the configured threshold. A silent client that the operator expects to be
 * running (or that was recently seen) raises a cooldown-gated alert. This is a dead-man's switch:
 * the alert comes from the <em>absence</em> of heartbeats, not from any error the client logged.
 */
@Component
public class LivenessService {
    /**
     * Below this many clients the fuse never applies: with one or two clients, "all of them" is an
     * ordinary outage rather than a signal that the heartbeat source itself failed.
     */
    static final int MIN_CLIENTS_FOR_FUSE = 3;

    private final int checkIntervalSeconds;
    private final int lookbackSeconds;
    private final int thresholdSeconds;
    private final int maxDownPercent;
    private final Set<String> expectedClients;
    private final HeartbeatSource heartbeatSource;
    private final Alerter alerter;
    private final AlertCooldownTracker cooldownTracker;

    private final Set<String> downClients = ConcurrentHashMap.newKeySet();
    private final Map<String, Sighting> statuses = new ConcurrentHashMap<>();
    /** Single-flight guard, run counters and the shared {@code /health} keys. */
    private final ScheduledJob job = new ScheduledJob("check");

    @Autowired
    public LivenessService(
            @Value("${liveness.check-interval-seconds}") int checkIntervalSeconds,
            @Value("${liveness.lookback-seconds}") int lookbackSeconds,
            @Value("${liveness.threshold-seconds}") int thresholdSeconds,
            @Value("${liveness.max-log-lines}") int maxLogLines,
            @Value("${liveness.expected-clients}") String expectedClients,
            @Value("${liveness.grafana-url}") String grafanaUrl,
            @Value("${liveness.grafana-api-token}") String grafanaApiToken,
            @Value("${liveness.grafana-loki-datasource-uid}") String grafanaLokiDatasourceUid,
            @Value("${liveness.loki-query}") String lokiQuery,
            @Value("${liveness.alert-url}") String alertUrl,
            @Value("${liveness.alert-cooldown-seconds}") int cooldownSeconds,
            @Value("${liveness.max-down-percent}") int maxDownPercent,
            Logger logger
    ) {
        this(checkIntervalSeconds, lookbackSeconds, thresholdSeconds, maxDownPercent,
                parseClients(expectedClients),
                new LokiHeartbeatClient(grafanaUrl, grafanaApiToken, grafanaLokiDatasourceUid, lokiQuery,
                        maxLogLines, httpClient(logger)),
                new AlertClient(alertUrl, httpClient(logger), SharedJson.mapper()),
                new AlertCooldownTracker(cooldownSeconds));
    }

    /** Grafana and the alert endpoint each get their own connection pool, both recorded. */
    private static HttpClient httpClient(Logger logger) {
        return LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), logger);
    }

    /** Existing tests build a service with the fuse at its default. */
    LivenessService(
            int checkIntervalSeconds,
            int lookbackSeconds,
            int thresholdSeconds,
            Set<String> expectedClients,
            HeartbeatSource heartbeatSource,
            Alerter alerter,
            AlertCooldownTracker cooldownTracker
    ) {
        this(checkIntervalSeconds, lookbackSeconds, thresholdSeconds, 100, expectedClients,
                heartbeatSource, alerter, cooldownTracker);
    }

    LivenessService(
            int checkIntervalSeconds,
            int lookbackSeconds,
            int thresholdSeconds,
            int maxDownPercent,
            Set<String> expectedClients,
            HeartbeatSource heartbeatSource,
            Alerter alerter,
            AlertCooldownTracker cooldownTracker
    ) {
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.lookbackSeconds = lookbackSeconds;
        this.thresholdSeconds = thresholdSeconds;
        this.maxDownPercent = maxDownPercent;
        this.expectedClients = expectedClients;
        this.heartbeatSource = heartbeatSource;
        this.alerter = alerter;
        this.cooldownTracker = cooldownTracker;
    }

    @Scheduled(fixedRateString = "${liveness.check-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledCheck() {
        job.runSafely(this::runCheck);
    }

    /**
     * Runs one liveness check. Single-flighted: the scheduler thread and the {@code /run-once}
     * handler both call this, and a slow Loki or alert round trip can outlive the interval.
     */
    public Map<String, Object> checkOnce() throws IOException, InterruptedException {
        return job.runOnce(this::runCheck);
    }

    public void recordRunOnceFailure(Exception exception) {
        job.recordFailure(exception);
    }

    /** {@code now} is the instant the run was stamped with, so a client's age is measured against
     * the same timestamp {@code /health} reports as {@code lastRunAt}. */
    private Map<String, Object> runCheck(Instant now) throws IOException, InterruptedException {
        Map<String, Instant> latest = heartbeatSource.latestHeartbeats(lookbackSeconds);

        // Check every client we expect plus every client we actually saw, so a configured client
        // that never came up is flagged and a discovered client that goes quiet is caught too.
        Set<String> candidates = new TreeSet<>(expectedClients);
        candidates.addAll(latest.keySet());

        // Decide first, alert second. The fuse below needs the whole picture, and alerting inside
        // this loop would already have sent half of them by the time the total was known.
        List<String> downNow = new ArrayList<>();
        for (String clientId : candidates) {
            Instant seen = latest.get(clientId);
            boolean down = seen == null || Duration.between(seen, now).getSeconds() > thresholdSeconds;
            statuses.put(clientId, new Sighting(down ? "down" : "up", seen));
            if (down) {
                downNow.add(clientId);
            } else {
                handleUp(clientId);
            }
        }
        int downCount = downNow.size();

        String refusal = refusedBecause(downCount, candidates.size());
        if (refusal == null) {
            for (String clientId : downNow) {
                handleDown(clientId, latest.get(clientId), now);
            }
        } else {
            // The status map and downClients still say what was observed — only the dispatch is
            // held. An operator looking at /health sees every client down *and* the reason the
            // alerts did not go out, which is strictly more information than N alerts would carry.
            downClients.addAll(downNow);
            job.recordAlertRejected(refusal);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("clientsChecked", candidates.size());
        response.put("clientsDown", downCount);
        response.put("summary", downCount == 0
                ? "All " + candidates.size() + " client(s) alive."
                : downCount + " of " + candidates.size() + " client(s) down.");
        if (refusal != null) {
            response.put("alertsSuppressed", true);
            response.put("alertsSuppressedReason", refusal);
        }
        return response;
    }

    /**
     * Why a check was not believed, or {@code null} when it was.
     *
     * <p>A heartbeat source that answers with nothing is indistinguishable from every client dying
     * in the same second, and the ways to get an empty answer are mundane: Loki restarted, its
     * retention is shorter than the lookback window, a label changed, or this service is pointed at
     * a different Loki than the one the clients push to. Any of them turns a monitoring service into
     * a pager storm. So a check at or above the fuse is reported loudly and not dispatched — the
     * same reasoning as insta's {@code INSTA_MAX_RETIRE_PERCENT}, which refuses to believe a walk
     * that would retire implausibly many followers at once.
     *
     * <p>The default is 100%, so this only ever withholds on a total wipeout; any partial outage
     * alerts exactly as before.
     */
    private String refusedBecause(int downCount, int checked) {
        if (maxDownPercent <= 0 || downCount == 0 || checked < MIN_CLIENTS_FOR_FUSE) {
            return null;
        }
        if (downCount * 100 < maxDownPercent * checked) {
            return null;
        }
        return ("%d of %d clients reported down in a single check, at or above the %d%% fuse. "
                + "Treating this as a heartbeat-source failure rather than a simultaneous outage; "
                + "no alerts were sent. Check that Loki is reachable and holds the lookback window.")
                .formatted(downCount, checked, maxDownPercent);
    }

    private void handleDown(String clientId, Instant lastSeen, Instant now) {
        downClients.add(clientId);
        if (!alerter.isEnabled()) {
            return;
        }
        long nowSeconds = now.getEpochSecond();
        if (!cooldownTracker.tryAcquire(clientId, nowSeconds)) {
            return;
        }
        try {
            switch (alerter.sendClientDown(clientId, lastSeen, thresholdSeconds)) {
                case DELIVERED -> job.recordAlertDelivered();
                // Terminal: hold the cooldown so a broken alert pipeline is not hammered, and
                // surface it on /health rather than failing silently.
                case REJECTED -> job.recordAlertRejected(
                        "Alert service rejected the liveness alert for " + clientId + ".");
                case FAILED -> cooldownTracker.rollback(clientId, nowSeconds);
            }
        } catch (IOException | InterruptedException exception) {
            // Transient network failure delivering the alert: free the slot so the next check
            // retries, and keep checking the remaining clients.
            cooldownTracker.rollback(clientId, nowSeconds);
            job.recordFailure(exception);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handleUp(String clientId) {
        // Recovered (or never down): forget any cooldown so the next outage alerts immediately.
        if (downClients.remove(clientId)) {
            cooldownTracker.clear(clientId);
        }
    }

    /** Current metrics and per-client status, surfaced on the shared {@code GET /health}. */
    public Map<String, Object> healthDetails() {
        Instant now = Instant.now();
        Map<String, Object> clients = new LinkedHashMap<>();
        statuses.forEach((clientId, sighting) -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", sighting.status());
            detail.put("lastSeen", sighting.lastSeen() == null ? null : sighting.lastSeen().toString());
            detail.put("secondsSinceLastSeen", sighting.lastSeen() == null
                    ? null : Duration.between(sighting.lastSeen(), now).getSeconds());
            clients.put(clientId, detail);
        });

        // This service's own configuration, then the run counters every scheduled job reports
        // (checksTotal, alerts, errors, timestamps), then what it saw.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("checkIntervalSeconds", checkIntervalSeconds);
        response.put("thresholdSeconds", thresholdSeconds);
        response.put("maxDownPercent", maxDownPercent);
        response.put("lookbackSeconds", lookbackSeconds);
        response.put("expectedClients", new LinkedHashSet<>(expectedClients));
        response.putAll(job.healthDetails());
        response.put("clients", clients);
        return response;
    }

    private static Set<String> parseClients(String configured) {
        Set<String> clients = new LinkedHashSet<>();
        if (configured != null) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(clients::add);
        }
        return clients;
    }

    private record Sighting(String status, Instant lastSeen) {
    }
}

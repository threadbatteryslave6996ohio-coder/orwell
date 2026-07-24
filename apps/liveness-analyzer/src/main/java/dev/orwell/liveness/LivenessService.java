package dev.orwell.liveness;

import dev.orwell.bootstrap.web.SharedJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls Loki for client heartbeats on a schedule and decides, per client, whether it has gone
 * silent longer than the configured threshold. A silent client that the operator expects to be
 * running (or that was recently seen) raises a cooldown-gated alert. This is a dead-man's switch:
 * the alert comes from the <em>absence</em> of heartbeats, not from any error the client logged.
 */
@Component
public class LivenessService {
    private final int checkIntervalSeconds;
    private final int lookbackSeconds;
    private final int thresholdSeconds;
    private final Set<String> expectedClients;
    private final HeartbeatSource heartbeatSource;
    private final Alerter alerter;
    private final AlertCooldownTracker cooldownTracker;

    private final Set<String> downClients = ConcurrentHashMap.newKeySet();
    private final Map<String, Sighting> statuses = new ConcurrentHashMap<>();
    private final AtomicBoolean checkInProgress = new AtomicBoolean(false);
    private final AtomicLong checksTotal = new AtomicLong();
    private final AtomicLong alertsSentTotal = new AtomicLong();
    private final AtomicLong alertsRejectedTotal = new AtomicLong();
    private final AtomicLong errorsTotal = new AtomicLong();
    private volatile Instant lastRunAt;
    private volatile Instant lastAlertAt;
    private volatile String lastError = "";

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
            @Value("${liveness.alert-cooldown-seconds}") int cooldownSeconds
    ) {
        this(checkIntervalSeconds, lookbackSeconds, thresholdSeconds, parseClients(expectedClients),
                new LokiHeartbeatClient(grafanaUrl, grafanaApiToken, grafanaLokiDatasourceUid, lokiQuery,
                        maxLogLines, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()),
                new AlertClient(alertUrl,
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                        SharedJson.mapper()),
                new AlertCooldownTracker(cooldownSeconds));
    }

    LivenessService(
            int checkIntervalSeconds,
            int lookbackSeconds,
            int thresholdSeconds,
            Set<String> expectedClients,
            HeartbeatSource heartbeatSource,
            Alerter alerter,
            AlertCooldownTracker cooldownTracker
    ) {
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.lookbackSeconds = lookbackSeconds;
        this.thresholdSeconds = thresholdSeconds;
        this.expectedClients = expectedClients;
        this.heartbeatSource = heartbeatSource;
        this.alerter = alerter;
        this.cooldownTracker = cooldownTracker;
    }

    @Scheduled(fixedRateString = "${liveness.check-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledCheck() {
        try {
            checkOnce();
        } catch (Exception exception) {
            recordFailure(exception);
        }
    }

    /**
     * Runs one liveness check. Single-flighted: the scheduler thread and the {@code /run-once}
     * handler both call this, and a slow Loki or alert round trip can outlive the interval.
     */
    public Map<String, Object> checkOnce() throws IOException, InterruptedException {
        if (!checkInProgress.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("success", false);
            busy.put("skipped", true);
            busy.put("summary", "A check is already in progress.");
            return busy;
        }
        try {
            return runCheck();
        } finally {
            checkInProgress.set(false);
        }
    }

    public void recordRunOnceFailure(Exception exception) {
        recordFailure(exception);
    }

    private Map<String, Object> runCheck() throws IOException, InterruptedException {
        checksTotal.incrementAndGet();
        Instant now = Instant.now();
        lastRunAt = now;

        Map<String, Instant> latest = heartbeatSource.latestHeartbeats(lookbackSeconds);

        // Check every client we expect plus every client we actually saw, so a configured client
        // that never came up is flagged and a discovered client that goes quiet is caught too.
        Set<String> candidates = new TreeSet<>(expectedClients);
        candidates.addAll(latest.keySet());

        int downCount = 0;
        for (String clientId : candidates) {
            Instant seen = latest.get(clientId);
            boolean down = seen == null || Duration.between(seen, now).getSeconds() > thresholdSeconds;
            statuses.put(clientId, new Sighting(down ? "down" : "up", seen));
            if (down) {
                downCount++;
                handleDown(clientId, seen, now);
            } else {
                handleUp(clientId);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("clientsChecked", candidates.size());
        response.put("clientsDown", downCount);
        response.put("summary", downCount == 0
                ? "All " + candidates.size() + " client(s) alive."
                : downCount + " of " + candidates.size() + " client(s) down.");
        return response;
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
                case DELIVERED -> {
                    alertsSentTotal.incrementAndGet();
                    lastAlertAt = Instant.now();
                }
                case REJECTED -> {
                    // Terminal: hold the cooldown so a broken alert pipeline is not hammered, and
                    // surface it on /health rather than failing silently.
                    alertsRejectedTotal.incrementAndGet();
                    lastError = "Alert service rejected the liveness alert for " + clientId + ".";
                }
                case FAILED -> cooldownTracker.rollback(clientId, nowSeconds);
            }
        } catch (IOException | InterruptedException exception) {
            // Transient network failure delivering the alert: free the slot so the next check
            // retries, and keep checking the remaining clients.
            cooldownTracker.rollback(clientId, nowSeconds);
            recordFailure(exception);
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

    private void recordFailure(Exception exception) {
        errorsTotal.incrementAndGet();
        lastError = String.valueOf(exception.getMessage());
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

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("checkIntervalSeconds", checkIntervalSeconds);
        response.put("thresholdSeconds", thresholdSeconds);
        response.put("lookbackSeconds", lookbackSeconds);
        response.put("expectedClients", new LinkedHashSet<>(expectedClients));
        response.put("checksTotal", checksTotal.get());
        response.put("alertsSentTotal", alertsSentTotal.get());
        response.put("alertsRejectedTotal", alertsRejectedTotal.get());
        response.put("errorsTotal", errorsTotal.get());
        response.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
        response.put("lastAlertAt", lastAlertAt == null ? null : lastAlertAt.toString());
        response.put("lastError", lastError);
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

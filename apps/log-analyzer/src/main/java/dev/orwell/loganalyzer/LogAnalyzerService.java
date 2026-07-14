package dev.orwell.loganalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bootstrap.SharedJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Polls Grafana Loki for recent error logs, asks an AI model whether they are important, and
 * forwards a cooldown-gated alert. The poll runs on a Spring schedule and is also reachable
 * on demand via {@code POST /run-once}; both paths funnel through the single-flight
 * {@link #pollOnce()} guard. Ported from the former hand-rolled HTTP server.
 */
@Component
public class LogAnalyzerService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final int lookbackSeconds;
    private final int maxLogLines;
    private final String grafanaUrl;
    private final String grafanaApiToken;
    private final String grafanaLokiDatasourceUid;
    private final String lokiQuery;
    private final AiAnalyzer aiAnalyzer;
    private final AlertClient alertClient;
    private final AlertCooldownTracker cooldownTracker;
    private final double minImportanceConfidence;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = SharedJson.mapper();
    private volatile Instant lastRunAt;
    private volatile Instant lastAlertAt;
    private volatile String lastDecisionSummary = "";
    private volatile String lastError = "";
    private final AtomicLong pollsTotal = new AtomicLong();
    private final AtomicLong alertsSentTotal = new AtomicLong();
    private final AtomicLong alertsRejectedTotal = new AtomicLong();
    private final AtomicLong errorsTotal = new AtomicLong();
    private final AtomicBoolean pollInProgress = new AtomicBoolean(false);

    public LogAnalyzerService(
            @Value("${loganalyzer.lookback-seconds}") int lookbackSeconds,
            @Value("${loganalyzer.max-log-lines}") int maxLogLines,
            @Value("${loganalyzer.grafana-url}") String grafanaUrl,
            @Value("${loganalyzer.grafana-api-token}") String grafanaApiToken,
            @Value("${loganalyzer.grafana-loki-datasource-uid}") String grafanaLokiDatasourceUid,
            @Value("${loganalyzer.loki-query}") String lokiQuery,
            @Value("${loganalyzer.alert-url}") String alertUrl,
            @Value("${loganalyzer.ai-api-url}") String aiApiUrl,
            @Value("${loganalyzer.ai-api-key}") String aiApiKey,
            @Value("${loganalyzer.ai-model}") String aiModel,
            @Value("${loganalyzer.ai-timeout-seconds}") int aiTimeoutSeconds,
            @Value("${loganalyzer.min-importance-confidence}") double minImportanceConfidence,
            @Value("${loganalyzer.alert-cooldown-seconds}") int cooldownSeconds
    ) {
        this.lookbackSeconds = lookbackSeconds;
        this.maxLogLines = maxLogLines;
        this.grafanaUrl = grafanaUrl;
        this.grafanaApiToken = grafanaApiToken;
        this.grafanaLokiDatasourceUid = grafanaLokiDatasourceUid;
        this.lokiQuery = lokiQuery;
        this.minImportanceConfidence = minImportanceConfidence;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Duration aiTimeout = Duration.ofSeconds(aiTimeoutSeconds);
        Model model = new OpenAiModel(aiApiUrl, aiApiKey, aiModel, aiTimeout, httpClient, SharedJson.mapper());
        this.aiAnalyzer = new AiAnalyzer(model, SharedJson.mapper());
        this.alertClient = new AlertClient(alertUrl, httpClient, SharedJson.mapper());
        this.cooldownTracker = new AlertCooldownTracker(cooldownSeconds);
    }

    @Scheduled(fixedRateString = "${loganalyzer.poll-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledPoll() {
        pollSafely();
    }

    /** Current metrics snapshot, surfaced on the shared {@code GET /health} endpoint. */
    public Map<String, Object> healthDetails() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pollsTotal", pollsTotal.get());
        response.put("alertsSentTotal", alertsSentTotal.get());
        response.put("alertsRejectedTotal", alertsRejectedTotal.get());
        response.put("errorsTotal", errorsTotal.get());
        response.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
        response.put("lastAlertAt", lastAlertAt == null ? null : lastAlertAt.toString());
        response.put("lastDecisionSummary", lastDecisionSummary);
        response.put("lastError", lastError);
        return response;
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception exception) {
            recordFailure(exception);
        }
    }

    private void recordFailure(Exception exception) {
        errorsTotal.incrementAndGet();
        lastError = exception.getMessage();
    }

    public Map<String, Object> pollOnce() throws IOException, InterruptedException {
        // Single-flight the poll: the scheduler thread and the /run-once handler thread both call
        // this, and a poll can outlive the interval (a slow AI call), so guard against overlap.
        if (!pollInProgress.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("success", false);
            busy.put("skipped", true);
            busy.put("summary", "A poll is already in progress.");
            return busy;
        }
        try {
            return runPoll();
        } finally {
            pollInProgress.set(false);
        }
    }

    public void recordRunOnceFailure(Exception exception) {
        recordFailure(exception);
    }

    private Map<String, Object> runPoll() throws IOException, InterruptedException {
        pollsTotal.incrementAndGet();
        lastRunAt = Instant.now();
        List<LogLine> logLines = fetchRecentErrorLogs();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("logCount", logLines.size());
        response.put("alertSent", false);

        if (logLines.isEmpty()) {
            lastDecisionSummary = "No matching logs found.";
            response.put("summary", lastDecisionSummary);
            return response;
        }

        if (!aiAnalyzer.isEnabled()) {
            lastDecisionSummary = "AI is disabled because the API url or key is missing.";
            response.put("summary", lastDecisionSummary);
            return response;
        }

        AiDecision decision = aiAnalyzer.analyze(lokiQuery, logLines);
        lastDecisionSummary = decision.message();
        response.put("analysis", Map.of(
                "important", decision.important(),
                "severity", decision.severity(),
                "title", decision.title(),
                "message", decision.message(),
                "reason", decision.reason(),
                "recommendedAction", decision.recommendedAction(),
                "confidence", decision.confidence()
        ));

        if (!decision.important() || decision.confidence() < minImportanceConfidence) {
            response.put("summary", "AI did not mark these logs as important.");
            return response;
        }

        if (!alertClient.isEnabled()) {
            response.put("summary", "Alert URL is disabled.");
            return response;
        }

        String fingerprint = fingerprint(decision);
        long nowSeconds = Instant.now().getEpochSecond();
        if (!cooldownTracker.tryAcquire(fingerprint, nowSeconds)) {
            response.put("summary", "Alert suppressed by cooldown.");
            response.put("alertSuppressed", true);
            return response;
        }

        AlertClient.Outcome outcome;
        try {
            outcome = alertClient.sendAlert(decision, logLines, lokiQuery, "grafana-loki");
        } catch (IOException | InterruptedException exception) {
            // Transient network failure: free the slot so the next poll retries.
            cooldownTracker.rollback(fingerprint, nowSeconds);
            throw exception;
        }
        switch (outcome) {
            case DELIVERED -> {
                response.put("alertSent", true);
                response.put("summary", "Alert sent.");
                alertsSentTotal.incrementAndGet();
                lastAlertAt = Instant.now();
            }
            case REJECTED -> {
                // Terminal client error: the service will keep rejecting this payload, so hold
                // the cooldown instead of hammering it. Surface it so a broken alerting pipeline
                // (e.g. bad auth) is visible on /health rather than failing silently.
                alertsRejectedTotal.incrementAndGet();
                lastError = "Alert service rejected the request.";
                response.put("summary", "Alert service rejected the request.");
            }
            case FAILED -> {
                // Transient error (5xx/408/429): release the slot so the next poll retries.
                cooldownTracker.rollback(fingerprint, nowSeconds);
                response.put("summary", "Alert delivery failed; will retry.");
            }
        }
        return response;
    }

    private List<LogLine> fetchRecentErrorLogs() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        long startMillis = now - (lookbackSeconds * 1000L);
        String url = buildGrafanaProxyUrl(startMillis * 1_000_000L, now * 1_000_000L, maxLogLines);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .GET();
        if (!grafanaApiToken.isBlank()) {
            builder.header("Authorization", "Bearer " + grafanaApiToken);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Grafana proxy query failed with status " + response.statusCode());
        }

        Map<String, Object> parsed = objectMapper.readValue(response.body(), MAP_TYPE);
        Map<String, Object> data = asMap(parsed.get("data"));
        List<Map<String, Object>> result = asListOfMaps(data.get("result"));
        List<LogLine> logLines = new ArrayList<>();
        for (Map<String, Object> stream : result) {
            Map<String, Object> labels = asMap(stream.get("stream"));
            List<List<Object>> values = asListOfLists(stream.get("values"));
            for (List<Object> value : values) {
                if (value.size() < 2) {
                    continue;
                }
                long tsNanos = Long.parseLong(String.valueOf(value.get(0)));
                String line = String.valueOf(value.get(1));
                LogLine logLine = new LogLine(
                        Instant.ofEpochMilli(tsNanos / 1_000_000L),
                        labels,
                        line
                );
                logLines.add(logLine);
            }
        }
        logLines.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));
        // Defense in depth: the query already sends limit=maxLogLines, but don't trust a proxy
        // or datasource to honor it — keep the prompt bounded to the newest maxLogLines lines.
        if (logLines.size() > maxLogLines) {
            return new ArrayList<>(logLines.subList(logLines.size() - maxLogLines, logLines.size()));
        }
        return logLines;
    }

    private String buildGrafanaProxyUrl(long startNanos, long endNanos, int limit) {
        if (grafanaUrl.isBlank()) {
            throw new IllegalStateException("GRAFANA_URL is required.");
        }
        if (grafanaLokiDatasourceUid.isBlank()) {
            throw new IllegalStateException("GRAFANA_LOKI_DATASOURCE_UID is required.");
        }
        StringBuilder builder = new StringBuilder(trimTrailingSlash(grafanaUrl));
        builder.append("/api/datasources/proxy/uid/")
                .append(encodePathSegment(grafanaLokiDatasourceUid))
                .append("/loki/api/v1/query_range");
        builder.append('?');
        builder.append("query=").append(encode(lokiQuery));
        builder.append("&start=").append(startNanos);
        builder.append("&end=").append(endNanos);
        builder.append("&limit=").append(limit);
        builder.append("&direction=backward");
        return builder.toString();
    }

    private String fingerprint(AiDecision decision) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create digest.", exception);
        }
        // Key the cooldown on the stable class of anomaly (title + severity), not the raw
        // log bodies, which vary every poll and would otherwise defeat the cooldown. Normalize
        // the title (lowercase, collapse non-alphanumeric runs) so cosmetic rewording of the
        // same anomaly across polls still maps to one fingerprint.
        digest.update(normalizeTitle(decision.title()).getBytes(StandardCharsets.UTF_8));
        digest.update(decision.severity().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String normalizeTitle(String title) {
        return NON_ALNUM.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> asListOfLists(Object value) {
        if (value instanceof List<?> list) {
            return (List<List<Object>>) list;
        }
        return List.of();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

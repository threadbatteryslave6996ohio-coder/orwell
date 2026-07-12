package dev.orwell.loganalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlertClient {
    /**
     * Result of an alert delivery attempt. {@code REJECTED} is a terminal error (most 4xx)
     * that will keep failing for the same payload, so the caller should not retry it;
     * {@code FAILED} is a transient error (5xx, 408, 429) worth retrying.
     */
    enum Outcome {
        DELIVERED,
        REJECTED,
        FAILED
    }

    private final String alertUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    AlertClient(String alertUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.alertUrl = alertUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    boolean isEnabled() {
        return !alertUrl.isBlank();
    }

    Outcome sendAlert(AiDecision decision, List<LogLine> logLines, String query, String source) throws IOException, InterruptedException {
        if (!isEnabled()) {
            return Outcome.REJECTED;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "important_log_anomaly");
        payload.put("source", source);
        payload.put("query", query);
        payload.put("importance", decision.importanceLabel());
        payload.put("confidence", decision.confidence());
        payload.put("title", decision.title());
        payload.put("message", decision.message());
        payload.put("reason", decision.reason());
        payload.put("recommendedAction", decision.recommendedAction());
        payload.put("logs", logLines);

        HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Outcome.DELIVERED;
        }
        // 408 Request Timeout and 429 Too Many Requests are transient despite being 4xx,
        // as is any 5xx; everything else (auth, bad request, ...) is terminal for this payload.
        if (status == 408 || status == 429 || status >= 500) {
            return Outcome.FAILED;
        }
        return Outcome.REJECTED;
    }
}

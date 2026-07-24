package dev.orwell.liveness;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Posts liveness alerts to the alert service. Mirrors the log-analyzer client's retry semantics. */
final class AlertClient implements Alerter {
    private final String alertUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    AlertClient(String alertUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.alertUrl = alertUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isEnabled() {
        return !alertUrl.isBlank();
    }

    @Override
    public Outcome sendClientDown(String clientId, Instant lastSeen, int thresholdSeconds)
            throws IOException, InterruptedException {
        if (!isEnabled()) {
            return Outcome.REJECTED;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "client_liveness_lost");
        payload.put("source", "liveness-analyzer");
        payload.put("severity", "critical");
        payload.put("clientId", clientId);
        payload.put("lastSeen", lastSeen == null ? null : lastSeen.toString());
        payload.put("thresholdSeconds", thresholdSeconds);
        payload.put("title", "Klippy client stopped sending heartbeats: " + clientId);
        payload.put("message", lastSeen == null
                ? "No heartbeat has been seen from client '" + clientId + "' within the lookback window."
                : "Client '" + clientId + "' last beat at " + lastSeen + ", over " + thresholdSeconds
                        + "s ago.");

        HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return Outcome.DELIVERED;
        }
        if (status == 408 || status == 429 || status >= 500) {
            return Outcome.FAILED;
        }
        return Outcome.REJECTED;
    }
}

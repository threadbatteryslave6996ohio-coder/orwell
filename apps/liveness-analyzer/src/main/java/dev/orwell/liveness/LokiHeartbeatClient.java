package dev.orwell.liveness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bootstrap.web.SharedJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads client heartbeat lines from Loki through Grafana's datasource proxy and reduces them to the
 * most recent sighting per client id. Only the newest beat per client matters for liveness, so the
 * whole window collapses to a {@code clientId -> latest timestamp} map.
 */
final class LokiHeartbeatClient implements HeartbeatSource {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String grafanaUrl;
    private final String grafanaApiToken;
    private final String grafanaLokiDatasourceUid;
    private final String lokiQuery;
    private final int maxLogLines;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = SharedJson.mapper();

    LokiHeartbeatClient(
            String grafanaUrl,
            String grafanaApiToken,
            String grafanaLokiDatasourceUid,
            String lokiQuery,
            int maxLogLines,
            HttpClient httpClient
    ) {
        this.grafanaUrl = grafanaUrl;
        this.grafanaApiToken = grafanaApiToken;
        this.grafanaLokiDatasourceUid = grafanaLokiDatasourceUid;
        this.lokiQuery = lokiQuery;
        this.maxLogLines = maxLogLines;
        this.httpClient = httpClient;
    }

    /** @return the most recent heartbeat instant per client id seen within {@code lookbackSeconds}. */
    @Override
    public Map<String, Instant> latestHeartbeats(int lookbackSeconds) throws IOException, InterruptedException {
        long nowMillis = System.currentTimeMillis();
        long startMillis = nowMillis - (lookbackSeconds * 1000L);
        String url = buildProxyUrl(startMillis * 1_000_000L, nowMillis * 1_000_000L);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
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

        Map<String, Instant> latest = new LinkedHashMap<>();
        for (Map<String, Object> stream : result) {
            for (List<Object> value : asListOfLists(stream.get("values"))) {
                if (value.size() < 2) {
                    continue;
                }
                Instant timestamp = parseNanos(value.get(0));
                String clientId = clientIdOf(String.valueOf(value.get(1)));
                if (timestamp == null || clientId == null) {
                    continue;
                }
                latest.merge(clientId, timestamp, (existing, candidate) ->
                        candidate.isAfter(existing) ? candidate : existing);
            }
        }
        return latest;
    }

    private static Instant parseNanos(Object rawTimestamp) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(String.valueOf(rawTimestamp)) / 1_000_000L);
        } catch (NumberFormatException exception) {
            // A non-numeric timestamp is one bad line, not a reason to fail the whole check.
            return null;
        }
    }

    private String clientIdOf(String line) {
        try {
            Object clientId = objectMapper.readValue(line, MAP_TYPE).get("clientId");
            return clientId == null ? null : String.valueOf(clientId);
        } catch (IOException exception) {
            // A non-JSON line cannot carry a clientId; skip it rather than fail the whole poll.
            return null;
        }
    }

    private String buildProxyUrl(long startNanos, long endNanos) {
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
        builder.append("&limit=").append(maxLogLines);
        builder.append("&direction=backward");
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> asListOfLists(Object value) {
        return value instanceof List<?> list ? (List<List<Object>>) list : List.of();
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

package dev.orwell.loganalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class OpenAiModel implements Model {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String SYSTEM_PROMPT = """
            You analyze production logs and decide whether they are important enough to alert on.
            Return only valid JSON with these keys:
            important (boolean),
            severity (string: info, low, medium, high, critical),
            title (short string),
            message (one-sentence summary),
            reason (short explanation),
            recommended_action (short next step),
            confidence (number from 0.0 to 1.0).
            """.trim();

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    OpenAiModel(String apiUrl, String apiKey, String model, Duration timeout, HttpClient httpClient, ObjectMapper objectMapper) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isEnabled() {
        return !apiUrl.isBlank() && !apiKey.isBlank();
    }

    @Override
    public String prompt(String content) throws IOException, InterruptedException {
        if (!isEnabled()) {
            throw new IllegalStateException("AI is disabled because the API url or key is missing.");
        }

        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", content)
                )
        );

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("AI request failed with status " + response.statusCode());
        }
        return parseResponseContent(response.body());
    }

    private String parseResponseContent(String responseBody) throws IOException {
        Map<String, Object> response = objectMapper.readValue(responseBody, MAP_TYPE);
        List<Map<String, Object>> choices = castList(response.get("choices"));
        if (choices.isEmpty()) {
            throw new IOException("AI response had no choices.");
        }
        Map<String, Object> firstChoice = choices.get(0);
        Map<String, Object> message = castMap(firstChoice.get("message"));
        Object content = message.get("content");
        if (content == null) {
            throw new IOException("AI response was missing assistant content.");
        }
        return stripCodeFences(String.valueOf(content));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        throw new IllegalArgumentException("Expected a list in AI response.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected an object in AI response.");
    }

    private static String stripCodeFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        trimmed = trimmed.substring(3);
        int newline = trimmed.indexOf('\n');
        if (newline >= 0) {
            // Multi-line fence: everything after ``` on the opening line is a language tag.
            trimmed = trimmed.substring(newline + 1);
        } else {
            // Single-line fence (e.g. ```{...}``` or ```json{...}```): skip a leading
            // language tag so the JSON payload isn't prefixed with stray letters.
            int start = 0;
            while (start < trimmed.length() && Character.isLetter(trimmed.charAt(start))) {
                start++;
            }
            trimmed = trimmed.substring(start);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}

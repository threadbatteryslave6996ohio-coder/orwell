package dev.orwell.loganalyzer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AiAnalyzer {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Model model;
    private final ObjectMapper objectMapper;

    AiAnalyzer(Model model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    boolean isEnabled() {
        return model.isEnabled();
    }

    AiDecision analyze(String query, List<LogLine> logLines) throws IOException, InterruptedException {
        if (!isEnabled()) {
            throw new IllegalStateException("AI is disabled because the API url or key is missing.");
        }
        String response = model.prompt(buildUserPrompt(query, logLines));
        Map<String, Object> decision = objectMapper.readValue(response, MAP_TYPE);
        return AiDecision.from(decision);
    }

    private String buildUserPrompt(String query, List<LogLine> logLines) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("logCount", logLines.size());
        payload.put("logs", logLines);
        return objectMapper.writeValueAsString(payload);
    }

}

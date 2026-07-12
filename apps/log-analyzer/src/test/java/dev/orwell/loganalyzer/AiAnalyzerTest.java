package dev.orwell.loganalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAnalyzerTest {
    @Test
    void delegatesPromptGenerationToModelAndParsesTheReturnedJson() throws Exception {
        String responseJson = """
                {"important":true,"severity":"high","title":"Disk pressure","message":"Disk pressure detected","reason":"Disk is near capacity","recommended_action":"Free space","confidence":0.91}
                """;
        RecordingModel model = new RecordingModel(responseJson);
        AiAnalyzer analyzer = new AiAnalyzer(model, new ObjectMapper().findAndRegisterModules());

        AiDecision decision = analyzer.analyze("loki-query", List.of(
                new LogLine(Instant.parse("2026-07-10T20:00:00Z"), Map.of("app", "jarvis"), "disk almost full")
        ));

        assertTrue(model.prompted);
        assertTrue(model.content.contains("\"query\":\"loki-query\""));
        assertTrue(model.content.contains("\"logCount\":1"));
        assertTrue(model.content.contains("\"line\":\"disk almost full\""));
        assertEquals("Disk pressure", decision.title());
        assertEquals("high", decision.severity());
        assertEquals(0.91, decision.confidence(), 0.0001);
    }

    private static final class RecordingModel implements Model {
        private final String response;
        private boolean prompted;
        private String content = "";

        private RecordingModel(String response) {
            this.response = response;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String prompt(String content) throws IOException {
            prompted = true;
            this.content = content;
            return response;
        }
    }
}

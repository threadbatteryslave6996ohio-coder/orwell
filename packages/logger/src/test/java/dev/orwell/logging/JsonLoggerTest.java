package dev.orwell.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLoggerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesOneJsonObjectPerLine() throws IOException {
        Path logFile = tempDir.resolve("alerting.log");
        JsonLogger logger = new JsonLogger(logFile);

        logger.info("Alert dispatched.");
        logger.error("Delivery failed.");

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());
        assertEquals("INFO", MAPPER.readTree(lines.get(0)).get("level").asText());
        assertEquals("Alert dispatched.", MAPPER.readTree(lines.get(0)).get("message").asText());
        assertEquals("ERROR", MAPPER.readTree(lines.get(1)).get("level").asText());
    }

    @Test
    void flattensMetadataAlongsideTheReservedFields() throws IOException {
        Path logFile = tempDir.resolve("alerting.log");

        new JsonLogger(logFile).info("Alert dispatched.", Map.of("channel", "email", "attempts", 2));

        JsonNode entry = MAPPER.readTree(Files.readAllLines(logFile).getFirst());
        assertEquals("email", entry.get("channel").asText());
        assertEquals(2, entry.get("attempts").asInt());
        assertFalse(entry.get("timestamp").asText().isBlank());
    }

    @Test
    void metadataCannotOverwriteReservedFields() throws IOException {
        Path logFile = tempDir.resolve("alerting.log");

        new JsonLogger(logFile).info("Real message.", Map.of("message", "spoofed", "level", "TRACE"));

        JsonNode entry = MAPPER.readTree(Files.readAllLines(logFile).getFirst());
        assertEquals("Real message.", entry.get("message").asText());
        assertEquals("INFO", entry.get("level").asText());
    }

    @Test
    void isUsableThroughTheLoggerInterface() throws IOException {
        Path logFile = tempDir.resolve("alerting.log");
        Logger logger = new JsonLogger(logFile);

        logger.log(new LogEntry(LogLevel.WARN, "Swapped sink.", null));

        assertTrue(Files.readString(logFile).contains("\"level\":\"WARN\""));
    }
}

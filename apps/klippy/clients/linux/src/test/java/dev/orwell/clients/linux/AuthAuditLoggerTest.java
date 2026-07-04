package dev.orwell.clients.linux;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.clients.core.ClipboardJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthAuditLoggerTest {
    @Test
    void serializesAuditFieldsAndEscapesMessageThroughSharedMapper() throws Exception {
        AuthAuditLogger logger = new AuthAuditLogger(null, Path.of("offline.json"), "linux-host", "http://auth:9090");

        JsonNode node = ClipboardJson.mapper().readTree(
                logger.toJson("refresh", "failed", "boom \"quoted\"\nline"));

        assertEquals("auth", node.get("type").asText());
        assertEquals("linux-host", node.get("clientId").asText());
        assertEquals("http://auth:9090", node.get("authServer").asText());
        assertEquals("refresh", node.get("operation").asText());
        assertEquals("failed", node.get("status").asText());
        assertEquals("boom \"quoted\"\nline", node.get("message").asText());
        assertTrue(node.hasNonNull("timestamp"));
    }

    @Test
    void usesUnsetPlaceholderAndEmptyMessageForNulls() throws Exception {
        AuthAuditLogger logger = new AuthAuditLogger(null, Path.of("offline.json"), "linux-host", null);

        JsonNode node = ClipboardJson.mapper().readTree(logger.toJson("refresh", "started", null));

        assertEquals("unset", node.get("authServer").asText());
        assertEquals("", node.get("message").asText());
    }
}

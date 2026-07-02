package dev.clippy.clients.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public final class ClipboardJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClipboardJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(ClipboardEntry entry) {
        try {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("clientId", entry.clientId())
                    .put("content", entry.content())
                    .put("timestamp", entry.timestamp().toString()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize clipboard entry.", exception);
        }
    }

    public static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException("Clipboard JSON entry is missing string field '" + field + "'.");
        }
        return value.textValue();
    }

    public static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IOException("Clipboard JSON entry is missing integer field '" + field + "'.");
        }
        return value.longValue();
    }

    public static Instant parseTimestamp(String value, String context) throws IOException {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IOException("Invalid timestamp in " + context + ": " + value, exception);
        }
    }
}

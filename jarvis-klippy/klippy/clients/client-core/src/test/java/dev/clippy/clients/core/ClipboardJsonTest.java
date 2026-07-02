package dev.clippy.clients.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardJsonTest {
    @Test
    void serializesClipboardContentWithoutManualEscaping() throws Exception {
        ClipboardEntry entry = new ClipboardEntry("client-a", "quote\" newline\n tab\t slash\\", Instant.EPOCH);

        var json = ClipboardJson.mapper().readTree(ClipboardJson.write(entry));

        assertEquals("client-a", json.get("clientId").textValue());
        assertEquals(entry.content(), json.get("content").textValue());
        assertEquals("1970-01-01T00:00:00Z", json.get("timestamp").textValue());
    }

    @Test
    void readsRequiredFieldsAndTimestamp() throws Exception {
        var json = ClipboardJson.mapper().readTree("""
                {"id":7,"clientId":"client-a","timestamp":"2026-06-23T12:00:00Z"}
                """);

        assertEquals(7, ClipboardJson.requiredLong(json, "id"));
        assertEquals("client-a", ClipboardJson.requiredText(json, "clientId"));
        assertEquals(Instant.parse("2026-06-23T12:00:00Z"),
                ClipboardJson.parseTimestamp(ClipboardJson.requiredText(json, "timestamp"), "test entry"));
    }

    @Test
    void rejectsMissingOrInvalidRequiredValues() throws Exception {
        var json = ClipboardJson.mapper().readTree("{\"id\":\"seven\"}");

        var missingText = assertThrows(IOException.class,
                () -> ClipboardJson.requiredText(json, "clientId"));
        var invalidLong = assertThrows(IOException.class,
                () -> ClipboardJson.requiredLong(json, "id"));
        var invalidTimestamp = assertThrows(IOException.class,
                () -> ClipboardJson.parseTimestamp("not-an-instant", "test entry"));

        assertTrue(missingText.getMessage().contains("clientId"));
        assertTrue(invalidLong.getMessage().contains("id"));
        assertTrue(invalidTimestamp.getMessage().contains("test entry"));
    }
}

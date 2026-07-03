package dev.clippy.clients.sync;

import dev.clippy.clients.core.ClipboardJson;

import java.io.IOException;

/**
 * A clipboard entry that could not be synchronized, captured for the dead-letter log. {@code stage}
 * records where it was rejected, {@code reason} explains why, and {@code original} is the raw JSON.
 */
record RejectedRecord(String stage, String reason, String original) {
    static RejectedRecord forRecord(String stage, String reason, ClipboardRecord record) {
        try {
            return new RejectedRecord(stage, reason, ClipboardJson.mapper().writeValueAsString(
                    ClipboardJson.mapper().createObjectNode()
                    .put("clientId", record.clientId())
                    .put("content", record.content())
                    .put("timestamp", record.timestamp().toString())));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not serialize rejected clipboard record.", exception);
        }
    }

    String toJson() throws IOException {
        return ClipboardJson.mapper().writeValueAsString(ClipboardJson.mapper().createObjectNode()
                .put("type", "dead-letter")
                .put("stage", stage)
                .put("reason", reason)
                .put("original", original));
    }
}

package dev.orwell.clients.sync;

import java.time.Instant;

/** A single clipboard entry read from the offline log and waiting to be synchronized. */
public record ClipboardRecord(String clientId, String content, Instant timestamp) {
}

package dev.orwell.clients.sync;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.clients.core.ClipboardApiClient;
import dev.orwell.clients.core.ClipboardEntry;
import dev.orwell.clients.core.ClipboardJson;
import dev.orwell.clients.core.env.ClientAuthSession;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Transport boundary for clipboard synchronization. Wraps the shared {@link ClipboardApiClient} and
 * speaks the clipboard HTTP API: paging through entries already present on the server and posting new
 * ones. It carries no synchronization policy; deciding what to send lives in {@link OfflineSyncService}.
 */
final class RemoteClipboardGateway {
    private static final int REMOTE_PAGE_SIZE = 1_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ClipboardApiClient apiClient;
    private final URI clipboardEndpoint;
    private final String clientId;

    RemoteClipboardGateway(URI clipboardEndpoint, ClientAuthSession authSession, String clientId) {
        this.apiClient = new ClipboardApiClient(clipboardEndpoint, authSession, REQUEST_TIMEOUT);
        this.clipboardEndpoint = clipboardEndpoint;
        this.clientId = clientId;
    }

    /** Pages through the server's clipboard entries in {@code [from, to]} and indexes them for dedup. */
    RemoteRecordIndex fetchPresentRecords(Instant from, Instant to) throws IOException, InterruptedException {
        RemoteRecordIndex records = new RemoteRecordIndex();
        Instant afterTimestamp = null;
        long afterId = 0;
        while (true) {
            String cursor = afterTimestamp == null ? "" : "&afterTimestamp=" + encode(afterTimestamp.toString())
                    + "&afterId=" + afterId;
            URI uri = URI.create(clipboardEndpoint + "?clientId=" + encode(clientId)
                    + "&from=" + encode(from.toString()) + "&to=" + encode(to.toString())
                    + "&limit=" + REMOTE_PAGE_SIZE + cursor);
            HttpResponse<String> response = apiClient.get(uri);
            requireSuccess(response, "query clipboard entries");

            JsonNode root = ClipboardJson.mapper().readTree(response.body());
            if (!root.isArray()) {
                throw new IOException("Server returned a non-array clipboard response.");
            }
            for (JsonNode node : root) {
                afterId = ClipboardJson.requiredLong(node, "id");
                afterTimestamp = ClipboardJson.parseTimestamp(
                        ClipboardJson.requiredText(node, "timestamp"), "server response");
                records.add(ClipboardJson.requiredText(node, "clientId"),
                        ClipboardJson.requiredText(node, "content"),
                        afterTimestamp);
            }
            if (root.size() < REMOTE_PAGE_SIZE) {
                return records;
            }
        }
    }

    /** Posts a single clipboard entry, returning the raw response so the caller can classify the status. */
    HttpResponse<String> send(ClipboardRecord record) throws IOException, InterruptedException {
        return apiClient.create(new ClipboardEntry(record.clientId(), record.content(), record.timestamp()));
    }

    static void requireSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Could not " + operation + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

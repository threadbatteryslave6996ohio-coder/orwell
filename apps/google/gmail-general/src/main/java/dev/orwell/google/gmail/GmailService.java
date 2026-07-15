package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.bootstrap.SharedJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gmail mailbox receiver: consumes Gmail push notifications, syncs new messages to a local store,
 * and fans them out to configured webhook clients. Ported from the former hand-rolled HTTP server.
 */
@Service
public class GmailService {
    private final ObjectMapper json = SharedJson.mapper();
    // Serializes checkpoint reads/writes to the .history file (sync vs watch).
    private final Object historyLock = new Object();
    // Coalesces concurrent pushes: one thread syncs, the rest record their historyId and return.
    private static final long NO_PENDING = Long.MIN_VALUE;
    private final AtomicLong pendingHistoryId = new AtomicLong(NO_PENDING);
    private final AtomicBoolean syncInProgress = new AtomicBoolean();
    private final HttpClient http = HttpClient.newHttpClient();
    private final HttpAuthenticationStrategy auth;
    private final String authClientId;
    private final String authClientSecret;
    private final Path store;
    private final String refreshToken;
    private final String gmailClientId;
    private final String gmailClientSecret;
    private final String pubsubTopic;
    private final List<String> clients;
    private volatile String currentAccessToken;

    public GmailService(
            @Value("${orwell.auth.base-url}") String authBaseUrl,
            @Value("${gmail.auth.client-id}") String authClientId,
            @Value("${gmail.auth.client-secret}") String authClientSecret,
            @Value("${gmail.store-dir}") String storeDir,
            @Value("${gmail.access-token}") String accessToken,
            @Value("${gmail.refresh-token}") String refreshToken,
            @Value("${gmail.client-id}") String gmailClientId,
            @Value("${gmail.client-secret}") String gmailClientSecret,
            @Value("${gmail.webhook-clients}") String webhookClients,
            @Value("${gmail.pubsub-topic}") String pubsubTopic
    ) throws IOException {
        this.auth = new HttpAuthenticationStrategy(authBaseUrl);
        this.authClientId = authClientId;
        this.authClientSecret = authClientSecret;
        this.store = Path.of(storeDir);
        this.currentAccessToken = accessToken;
        this.refreshToken = refreshToken;
        this.gmailClientId = gmailClientId;
        this.gmailClientSecret = gmailClientSecret;
        this.pubsubTopic = pubsubTopic;
        this.clients = Arrays.stream(webhookClients.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        Files.createDirectories(store);
    }

    /** Handles a Gmail Pub/Sub push envelope, syncing new history since the last checkpoint. */
    public void handlePubsub(byte[] body) throws Exception {
        JsonNode envelope = json.readTree(body);
        String encoded = envelope.path("message").path("data").asText("");
        if (!encoded.isBlank()) {
            JsonNode notice = json.readTree(Base64.getUrlDecoder().decode(encoded));
            requestSync(notice.path("historyId").asLong(0));
        }
    }

    /**
     * Coalesces concurrent pushes so at most one sync runs at a time and no request thread ever
     * waits on another sync's network I/O: latecomers record the highest requested historyId and
     * return immediately. Acking a coalesced push early is safe because the checkpoint is the
     * source of truth — it only advances after a successful sync, so unprocessed history is
     * re-scanned on the next push.
     */
    private void requestSync(long historyId) throws Exception {
        if (historyId <= 0) {
            // A push without a usable historyId (manual test publish, foreign producer on the
            // topic) must not run a sync — it would checkpoint '0' and poison the cursor.
            return;
        }
        pendingHistoryId.accumulateAndGet(historyId, Math::max);
        while (pendingHistoryId.get() != NO_PENDING) {
            if (!syncInProgress.compareAndSet(false, true)) {
                return; // the in-flight sync drains pendingHistoryId
            }
            try {
                long target;
                while ((target = pendingHistoryId.getAndSet(NO_PENDING)) != NO_PENDING) {
                    try {
                        sync(target);
                    } catch (Exception exception) {
                        // The drain consumed the target but the work didn't happen. Put it back so
                        // the retry (this push's Pub/Sub redelivery, or any later push) re-syncs it —
                        // otherwise coalesced pushes that were already acked lose their work.
                        pendingHistoryId.accumulateAndGet(target, Math::max);
                        throw exception;
                    }
                }
            } finally {
                syncInProgress.set(false);
            }
            // Re-check: a push may have registered between the drain and releasing the flag.
        }
    }

    /** Thrown when the watch registration is requested without a configured Pub/Sub topic. */
    public static final class MissingPubsubTopicException extends RuntimeException {
        MissingPubsubTopicException() {
            super("GMAIL_PUBSUB_TOPIC is required");
        }
    }

    /** Registers a Gmail watch against the configured Pub/Sub topic. */
    public JsonNode watch() throws Exception {
        if (pubsubTopic.isBlank()) {
            throw new MissingPubsubTopicException();
        }
        ObjectNode body = json.createObjectNode().put("topicName", pubsubTopic);
        body.putArray("labelIds").add("INBOX");
        JsonNode result = gmail("https://gmail.googleapis.com/gmail/v1/users/me/watch", body);
        if (result.has("historyId")) {
            synchronized (historyLock) {
                Files.writeString(store.resolve(".history"), result.path("historyId").asText());
            }
        }
        return result;
    }

    // Runs single-flight (see requestSync); historyLock guards only the checkpoint file accesses
    // so no lock is ever held across network I/O.
    private void sync(long historyId) throws Exception {
        long previous;
        synchronized (historyLock) {
            previous = Files.exists(store.resolve(".history"))
                    ? Long.parseLong(Files.readString(store.resolve(".history")).trim()) : historyId - 1;
        }
        // One auth login per sync batch (lazily created, reused across messages and clients).
        var webhookLogin = new AtomicReference<LoginHttpResponse>();
        String page = "";
        do {
            String url = "https://gmail.googleapis.com/gmail/v1/users/me/history?startHistoryId=" + previous;
            if (!page.isBlank()) {
                url += "&pageToken=" + page;
            }
            JsonNode history = gmail(url);
            for (JsonNode item : history.path("history")) {
                for (JsonNode added : item.path("messagesAdded")) {
                    String id = added.path("message").path("id").asText();
                    if (!id.isBlank()) {
                        save(gmail("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id + "?format=full"),
                                webhookLogin);
                    }
                }
            }
            page = history.path("nextPageToken").asText("");
        } while (!page.isBlank());
        synchronized (historyLock) {
            // Monotonic advance only: watch() may have written a newer checkpoint while this sync
            // was doing network I/O; a stale overwrite here would rewind it and replay history.
            long current = Files.exists(store.resolve(".history"))
                    ? Long.parseLong(Files.readString(store.resolve(".history")).trim()) : Long.MIN_VALUE;
            if (historyId > current) {
                Files.writeString(store.resolve(".history"), Long.toString(historyId));
            }
        }
    }

    private void save(JsonNode raw, AtomicReference<LoginHttpResponse> webhookLogin) throws Exception {
        GmailMessage message = new GmailMessage(raw.path("id").asText(), raw.path("threadId").asText(),
                header(raw, "Subject"), header(raw, "From"), header(raw, "To"),
                raw.path("internalDate").asLong(Instant.now().toEpochMilli()), textPart(raw.path("payload")));
        Path file = store.resolve(message.id() + ".json");
        boolean newMessage = Files.notExists(file);
        json.writeValue(file.toFile(), message);
        if (!newMessage) {
            return;
        }
        String payload = json.writeValueAsString(message);
        for (String client : clients) {
            try {
                HttpResponse<Void> response = postWebhook(client, payload, webhookLogin);
                if (response.statusCode() == 401) {
                    // The batch-cached token may have expired mid-sync: refresh once and retry.
                    webhookLogin.set(null);
                    response = postWebhook(client, payload, webhookLogin);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    System.err.println("Webhook " + client + " rejected Gmail message "
                            + message.id() + ": HTTP " + response.statusCode());
                }
            } catch (Exception exception) {
                System.err.println("Could not notify " + client + " of Gmail message "
                        + message.id() + ": " + exception.getMessage());
            }
        }
    }

    /**
     * Posts one webhook delivery, reusing the batch-cached login (one login per sync batch instead
     * of one per message x client); a null reference triggers a fresh login.
     */
    private HttpResponse<Void> postWebhook(String client, String payload,
            AtomicReference<LoginHttpResponse> webhookLogin) throws Exception {
        LoginHttpResponse authenticated = webhookLogin.get();
        if (authenticated == null) {
            authenticated = auth.login(authClientId, authClientSecret);
            webhookLogin.set(authenticated);
        }
        return http.send(HttpRequest.newBuilder(URI.create(client))
                        .header("Content-Type", "application/json")
                        .header("X-Client-Id", authenticated.clientId())
                        .header("Authorization", "Bearer " + authenticated.token())
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private JsonNode gmail(String url) throws Exception {
        return gmail(url, null);
    }

    private JsonNode gmail(String url, JsonNode body) throws Exception {
        if (currentAccessToken.isBlank() && refreshToken.isBlank()) {
            throw new IllegalStateException("Set GMAIL_ACCESS_TOKEN or GMAIL_REFRESH_TOKEN");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json");
        if (body != null) {
            builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && !refreshToken.isBlank()) {
            String refreshed = forceRefreshAccessToken();
            builder = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + refreshed)
                    .header("Content-Type", "application/json");
            if (body != null) {
                builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                builder.GET();
            }
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() >= 300) {
            throw new IOException("Gmail API " + response.statusCode() + ": " + response.body());
        }
        return json.readTree(response.body());
    }

    private synchronized String accessToken() throws Exception {
        if (!currentAccessToken.isBlank()) {
            return currentAccessToken;
        }
        currentAccessToken = refreshAccessToken();
        return currentAccessToken;
    }

    private synchronized String forceRefreshAccessToken() throws Exception {
        currentAccessToken = refreshAccessToken();
        return currentAccessToken;
    }

    private String refreshAccessToken() throws Exception {
        String form = "client_id=" + enc(gmailClientId)
                + "&client_secret=" + enc(gmailClientSecret)
                + "&refresh_token=" + enc(refreshToken) + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("OAuth refresh failed: " + response.body());
        }
        String token = json.readTree(response.body()).path("access_token").asText("");
        if (token.isBlank()) {
            throw new IOException("OAuth refresh response did not contain access_token");
        }
        return token;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String header(JsonNode raw, String name) {
        for (JsonNode header : raw.path("payload").path("headers")) {
            if (name.equalsIgnoreCase(header.path("name").asText())) {
                return header.path("value").asText();
            }
        }
        return "";
    }

    private static String textPart(JsonNode node) {
        if (node.path("mimeType").asText().equals("text/plain") && node.has("body")) {
            return decode(node.path("body").path("data").asText());
        }
        for (JsonNode part : node.path("parts")) {
            String text = textPart(part);
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }
}

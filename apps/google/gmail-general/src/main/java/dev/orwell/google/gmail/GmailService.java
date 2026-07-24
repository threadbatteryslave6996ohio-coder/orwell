package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.bootstrap.web.SharedJson;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persists each received mailbox message to the {@code gmail} database and fans it out to the
 * configured webhook clients, authenticating every delivery with a bearer token from the auth
 * server. The ingestion side (reading the mailbox) lives in {@link ImapMailPoller}; this service
 * owns only the store-and-forward half.
 */
@Service
public class GmailService {
    private final ObjectMapper json = SharedJson.mapper();
    private final HttpClient http = HttpClient.newHttpClient();
    // One token is cached and reused across deliveries; a 401 refreshes it and the call is retried.
    private final ClientAuthSession session;
    private final EmailMessageRepository repository;
    private final List<String> clients;
    private final Logger logger;

    public GmailService(
            @Value("${orwell.auth.base-url}") String authBaseUrl,
            @Value("${gmail.auth.client-id}") String authClientId,
            @Value("${gmail.auth.client-secret}") String authClientSecret,
            @Value("${gmail.webhook-clients}") String webhookClients,
            EmailMessageRepository repository,
            Logger logger
    ) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.session = new ClientAuthSession(authBaseUrl, authClientId, authClientSecret, null);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clients = Arrays.stream(webhookClients.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    /**
     * Saves the message row and, if it was not already stored, forwards it to every webhook
     * client. A unique constraint on {@code message_id} is the dedup key, so a redelivered
     * message (e.g. re-fetched after a checkpoint resync) is stored once and fanned out once.
     */
    public void deliver(GmailMessage message, long imapUid) {
        if (repository.existsByMessageId(message.id())) {
            return;
        }
        repository.save(new EmailMessageEntity(
                message.id(), imapUid, message.threadId(), message.subject(),
                message.from(), message.to(), Instant.ofEpochMilli(message.receivedAt()),
                message.body(), Instant.now()));
        forwardToWebhooks(message);
    }

    private void forwardToWebhooks(GmailMessage message) {
        if (clients.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = json.writeValueAsString(message);
        } catch (Exception exception) {
            logger.error("Could not serialize Gmail message for webhook delivery.",
                    Map.of("messageId", message.id(), "error", String.valueOf(exception.getMessage())));
            return;
        }
        for (String client : clients) {
            try {
                HttpResponse<Void> response = postWebhook(client, payload);
                if (response.statusCode() == 401 && session.refreshIfUnauthorized(401)) {
                    // The cached token may have expired: refresh once and retry.
                    response = postWebhook(client, payload);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    logger.error("Webhook rejected Gmail message.", Map.of(
                            "client", client,
                            "messageId", message.id(),
                            "statusCode", response.statusCode()));
                }
            } catch (Exception exception) {
                // getMessage() is null for plenty of exception types, so this map must accept nulls.
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("client", client);
                metadata.put("messageId", message.id());
                metadata.put("error", exception.getMessage());
                logger.error("Could not notify webhook client of Gmail message.", metadata);
            }
        }
    }

    private HttpResponse<Void> postWebhook(String client, String payload) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(client))
                        .header("Content-Type", "application/json")
                        .header("X-Client-Id", session.clientId())
                        .header("Authorization", "Bearer " + session.token())
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}

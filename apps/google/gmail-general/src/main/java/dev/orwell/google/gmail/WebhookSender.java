package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.bootstrap.web.SharedJson;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Posts one message to one webhook URL, authenticated as this service.
 *
 * <p>Shared by the two delivery paths so the auth handling exists once: {@link GmailService} uses
 * it for the legacy {@code GMAIL_WEBHOOK_CLIENTS} broadcast, and {@link WebhookDeliveryJob} for
 * cursor-tracked per-subscription delivery. One token is cached and reused across deliveries; a
 * 401 refreshes it and the call is retried once.
 */
@Component
public class WebhookSender {
    private final ObjectMapper json = SharedJson.mapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ClientAuthSession session;
    private final Logger logger;

    public WebhookSender(
            @Value("${orwell.auth.base-url}") String authBaseUrl,
            @Value("${gmail.auth.client-id}") String authClientId,
            @Value("${gmail.auth.client-secret}") String authClientSecret,
            Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.session = new ClientAuthSession(authBaseUrl, authClientId, authClientSecret, null);
    }

    /**
     * @return {@code true} if the receiver accepted the message with a 2xx. Every other outcome —
     *         a non-2xx status, a transport failure, an unserializable payload — is logged and
     *         reported as {@code false}. Callers decide what a failure means: the broadcast path
     *         drops it, the subscription path leaves its cursor unadvanced and retries.
     */
    public boolean send(String url, GmailMessage message) {
        String payload;
        try {
            payload = json.writeValueAsString(message);
        } catch (Exception exception) {
            logger.error("Could not serialize Gmail message for webhook delivery.", Map.of(
                    "messageId", message.id(),
                    "error", String.valueOf(exception.getMessage())));
            return false;
        }
        try {
            HttpResponse<Void> response = post(url, payload);
            if (response.statusCode() == 401 && session.refreshIfUnauthorized(401)) {
                // The cached token may have expired: refresh once and retry.
                response = post(url, payload);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.error("Webhook rejected Gmail message.", Map.of(
                        "client", url,
                        "account", message.account(),
                        "messageId", message.id(),
                        "statusCode", response.statusCode()));
                return false;
            }
            return true;
        } catch (Exception exception) {
            // getMessage() is null for plenty of exception types, so this map must accept nulls.
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("client", url);
            metadata.put("account", message.account());
            metadata.put("messageId", message.id());
            metadata.put("error", exception.getMessage());
            logger.error("Could not notify webhook client of Gmail message.", metadata);
            return false;
        }
    }

    private HttpResponse<Void> post(String url, String payload) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("X-Client-Id", session.clientId())
                        .header("Authorization", "Bearer " + session.token())
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}

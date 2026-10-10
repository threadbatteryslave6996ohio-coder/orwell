package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.bootstrap.web.SharedJson;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 *
 * <p><strong>Both timeouts below are load-bearing, and this is the one client in the repo where
 * the destination is not ours.</strong> A subscriber registers its own URL, and
 * {@link WebhookDeliveryJob} walks every subscription in turn on the shared {@code @Scheduled}
 * thread. Without a bound, a subscriber whose host accepts the connection and then never answers
 * blocks that walk forever: the job's per-subscription {@code catch} never fires, because a hang is
 * not an exception, so the isolation it exists to provide silently does not apply. That thread is
 * also the one {@code ImapMailPoller} is scheduled on, so the same stall stops mail arriving at
 * all. Bounded, a dead subscriber costs one timeout per round and is reported like any other
 * failure.
 */
@Component
public class WebhookSender {
    /** Long enough for a slow-but-working receiver, short enough that a dead one is not a stall. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper json = SharedJson.mapper();
    private final HttpClient http;
    private final ClientAuthSession session;
    private final Logger logger;

    private final Duration requestTimeout;

    /**
     * Without a workspace, which means {@link AuthWorkspaces#DEFAULT} — the behaviour every caller
     * had before {@code AUTH_WORKSPACE} was threaded through. Kept so callers that do not configure
     * one (tests, and anything embedding this) need not pass a null.
     */
    public WebhookSender(
            String authBaseUrl, String authClientId, String authClientSecret, Logger logger) {
        this(authBaseUrl, null, authClientId, authClientSecret, logger, REQUEST_TIMEOUT);
    }

    /** {@code @Autowired} because the test constructor below makes the choice ambiguous otherwise. */
    @Autowired
    public WebhookSender(
            @Value("${orwell.auth.base-url}") String authBaseUrl,
            // The same key the shared inbound guard reads. Hardcoding AuthWorkspaces.DEFAULT here
            // meant that with AUTH_WORKSPACE=team-a this service validated its callers against
            // team-a while logging *itself* in at `def` — able to authenticate everyone but itself.
            @Value("${orwell.auth.workspace:}") String authWorkspace,
            @Value("${gmail.auth.client-id}") String authClientId,
            @Value("${gmail.auth.client-secret}") String authClientSecret,
            Logger logger) {
        this(authBaseUrl, authWorkspace, authClientId, authClientSecret, logger, REQUEST_TIMEOUT);
    }

    /** Timeout injectable so a test can prove the bound without waiting out the real one. */
    WebhookSender(
            String authBaseUrl,
            String authClientId,
            String authClientSecret,
            Logger logger,
            Duration requestTimeout) {
        this(authBaseUrl, null, authClientId, authClientSecret, logger, requestTimeout);
    }

    /** Timeout injectable so a test can prove the bound without waiting out the real one. */
    WebhookSender(
            String authBaseUrl,
            String authWorkspace,
            String authClientId,
            String authClientSecret,
            Logger logger,
            Duration requestTimeout) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), logger);
        // AuthWorkspaces.orDefault turns null or blank back into DEFAULT, so an unset key behaves as before.
        this.session = new ClientAuthSession(
                authBaseUrl, AuthWorkspaces.orDefault(authWorkspace), authClientId, authClientSecret, null,
                logger);
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
                        // Bounds a receiver that accepts the request and then stops responding;
                        // the connect timeout alone does not, because it is already connected.
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .header("X-Client-Id", session.clientId())
                        .header("Authorization", "Bearer " + session.token())
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.discarding());
    }
}

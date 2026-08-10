package dev.orwell.bucket.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.bootstrap.web.SharedJson;
import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Posts one frame to one subscriber URL.
 *
 * <p>Authentication is optional here, unlike gmail's equivalent: detection has historically run
 * without auth configured, so a blank {@code DETECTION_AUTH_CLIENT_ID} sends unauthenticated
 * deliveries rather than refusing to start. When it is set, one token is cached and reused, and a
 * 401 refreshes it and retries the call once.
 */
@Component
public class FrameSender {
    private final ObjectMapper json = SharedJson.mapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final ClientAuthSession session;
    private final Logger logger;

    public FrameSender(
            @Value("${orwell.auth.base-url:}") String authBaseUrl,
            @Value("${detection.auth.client-id}") String authClientId,
            @Value("${detection.auth.client-secret}") String authClientSecret,
            Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.session = authClientId == null || authClientId.isBlank()
                ? null
                : new ClientAuthSession(authBaseUrl, authClientId, authClientSecret, null);
    }

    /**
     * @return {@code true} if the subscriber accepted the frame with a 2xx. Every other outcome —
     *         a non-2xx status, a transport failure, an unserializable payload — is logged and
     *         reported as {@code false}, which leaves the caller's cursor unadvanced so the frame
     *         is retried on a later round.
     */
    public boolean send(String url, FrameEventEntity frame) {
        String payload;
        try {
            payload = json.writeValueAsString(body(frame));
        } catch (Exception exception) {
            logger.error("Could not serialize frame for delivery.", Map.of(
                    "frameId", frame.getId(),
                    "error", String.valueOf(exception.getMessage())));
            return false;
        }
        try {
            HttpResponse<Void> response = post(url, payload);
            if (response.statusCode() == 401 && session != null && session.refreshIfUnauthorized(401)) {
                response = post(url, payload);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.error("Subscriber rejected a frame.", Map.of(
                        "client", url,
                        "source", frame.getSource(),
                        "frameId", frame.getId(),
                        "statusCode", response.statusCode()));
                return false;
            }
            return true;
        } catch (Exception exception) {
            // getMessage() is null for plenty of exception types, so this map must accept nulls.
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("client", url);
            metadata.put("source", frame.getSource());
            metadata.put("frameId", frame.getId());
            metadata.put("error", exception.getMessage());
            logger.error("Could not deliver a frame to a subscriber.", metadata);
            return false;
        }
    }

    private static Map<String, Object> body(FrameEventEntity frame) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("frameId", frame.getId());
        body.put("source", frame.getSource());
        body.put("frameIndex", frame.getFrameIndex());
        body.put("capturedAt", frame.getCapturedAt().toString());
        body.put("sha256", frame.getSha256());
        body.put("changed", frame.isChanged());
        body.put("changedFraction", frame.getChangedFraction());
        body.put("frameBase64", Base64.getEncoder().encodeToString(frame.getFrameBytes()));
        return body;
    }

    private HttpResponse<Void> post(String url, String payload) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (session != null) {
            request.header("X-Client-Id", session.clientId())
                    .header("Authorization", "Bearer " + session.token());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }
}

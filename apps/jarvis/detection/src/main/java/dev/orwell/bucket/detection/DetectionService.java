package dev.orwell.bucket.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bootstrap.web.SharedJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core detection logic: decode a frame, run person detection, and fire a cooldown-gated alert.
 * Extracted from the former hand-rolled HTTP server so the transport (Spring MVC) stays thin.
 */
@Service
public class DetectionService {
    private final String alertUrl;
    private final PersonDetector detector;
    private final CooldownTracker cooldowns;
    private final ObjectMapper mapper = SharedJson.mapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Atomic: the detect endpoint is served by Tomcat's concurrent request pool, so the counters
    // must not use non-atomic +=/++ (the former hand-rolled server ran handlers serialized).
    private final AtomicInteger detectionsTotal = new AtomicInteger();
    private final AtomicInteger alertsSentTotal = new AtomicInteger();

    public DetectionService(
            @Value("${detection.alert-url}") String alertUrl,
            @Value("${detection.cooldown-seconds}") int cooldownSeconds,
            @Value("${detection.min-confidence}") double minConfidence
    ) {
        this.alertUrl = alertUrl;
        this.cooldowns = new CooldownTracker(cooldownSeconds);
        this.detector = new HogPersonDetector(minConfidence);
    }

    static DetectionService fromEnv(dev.orwell.env.Env env) {
        return new DetectionService(
                env.get(DetectionEnvs.DETECTION_ALERT_URL),
                env.get(DetectionEnvs.DETECTION_ALERT_COOLDOWN_SECONDS),
                env.get(DetectionEnvs.DETECTION_MIN_CONFIDENCE)
        );
    }

    public int detectionsTotal() {
        return detectionsTotal.get();
    }

    public int alertsSentTotal() {
        return alertsSentTotal.get();
    }

    /**
     * Runs detection over the request payload. Throws {@link InvalidFrameException} for a bad
     * frame (mapped to 400 by the controller); any other runtime failure surfaces as 500.
     */
    public Map<String, Object> detect(Map<String, Object> payload) {
        byte[] frameBytes = decodeFrame(payload);
        String frameSha = sha256Hex(frameBytes);

        String source = String.valueOf(payload.getOrDefault("source", payload.getOrDefault("streamId", "unknown")));
        Object frameIndex = payload.get("frameIndex");
        Object timestamp = payload.get("timestamp");

        List<Detection> detections = detector.detect(frameBytes);

        detectionsTotal.addAndGet(detections.size());
        boolean alertSent = false;
        String alertError = null;
        if (!detections.isEmpty() && cooldowns.allow(source, Instant.now().getEpochSecond())) {
            // LinkedHashMap, not Map.of: frameIndex/timestamp are optional request fields and may be
            // null, which Map.of rejects with an NPE.
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("event", "person_detected");
            alert.put("source", source);
            alert.put("frameIndex", frameIndex);
            alert.put("timestamp", timestamp);
            alert.put("frameSha256", frameSha);
            alert.put("personCount", detections.size());
            alert.put("boxes", detections);
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(alert)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    alertSent = true;
                    alertsSentTotal.incrementAndGet();
                }
            } catch (Exception exception) {
                alertError = exception.getMessage();
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("source", source);
        response.put("frameIndex", frameIndex);
        response.put("timestamp", timestamp);
        response.put("personCount", detections.size());
        response.put("alertSent", alertSent);
        response.put("alertError", alertError);
        response.put("boxes", detections);
        return response;
    }

    private byte[] decodeFrame(Map<String, Object> payload) {
        Object encoded = payload.get("frameBase64");
        if (encoded == null) {
            throw new InvalidFrameException("frameBase64 missing");
        }
        byte[] frame;
        try {
            frame = Base64.getDecoder().decode(String.valueOf(encoded));
        } catch (IllegalArgumentException exception) {
            throw new InvalidFrameException("frameBase64 is not valid base64");
        }
        String expectedSha = String.valueOf(payload.getOrDefault("frameSha256", ""));
        if (!expectedSha.isBlank() && !expectedSha.equals(sha256Hex(frame))) {
            throw new InvalidFrameException("frame hash mismatch");
        }
        return frame;
    }

    /** A malformed frame in the request (bad/missing base64 or hash mismatch): a client error (400). */
    public static final class InvalidFrameException extends RuntimeException {
        InvalidFrameException(String message) {
            super(message);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash frame.", exception);
        }
    }
}

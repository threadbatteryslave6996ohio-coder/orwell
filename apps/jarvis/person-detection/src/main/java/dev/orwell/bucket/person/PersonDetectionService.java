package dev.orwell.bucket.person;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bucket.frame.client.Frame;
import dev.orwell.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs person detection over each frame the hub sends, and fires a cooldown-gated alert.
 *
 * <p>It is a {@code FrameListener}, not an HTTP endpoint. Producers push a frame once, to the hub;
 * this watches the hub's stream and gets every frame that arrives. That removed the whole intake
 * half of this service — a controller, an engine-neutral endpoint, two engine main classes, and
 * envelope parsing — because a frame now arrives already decoded and already verified.
 *
 * <p>The consequence to know: detection is <strong>asynchronous</strong>. Nothing gets a verdict
 * back in a response any more; a detection becomes an alert to {@code apps/alerting} or it becomes
 * a counter on {@code /health}. That is the trade for pushing a frame once instead of once per
 * interested service.
 *
 * <p>Still the expensive half of jarvis: {@link HogPersonDetector} decodes the whole frame through
 * {@code ImageIO} and scans it, now on the frame client's reader thread. A detection slower than
 * the frame rate therefore makes this watcher fall behind, and the hub starts dropping this
 * connection's oldest frames rather than buffering them without bound — the durable subscription
 * re-fetches what was dropped on the next reconnect.
 */
public class PersonDetectionService {
    private final String alertUrl;
    private final PersonDetector detector;
    private final CooldownTracker cooldowns;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AtomicInteger framesExaminedTotal = new AtomicInteger();
    private final AtomicInteger detectionsTotal = new AtomicInteger();
    private final AtomicInteger alertsSentTotal = new AtomicInteger();
    private final AtomicInteger undecodableFramesTotal = new AtomicInteger();

    public PersonDetectionService(
            String alertUrl, int cooldownSeconds, double minConfidence, Logger logger) {
        this.alertUrl = Objects.requireNonNull(alertUrl, "alertUrl");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cooldowns = new CooldownTracker(cooldownSeconds);
        this.detector = new HogPersonDetector(minConfidence);
    }

    public int framesExaminedTotal() {
        return framesExaminedTotal.get();
    }

    public int detectionsTotal() {
        return detectionsTotal.get();
    }

    public int alertsSentTotal() {
        return alertsSentTotal.get();
    }

    /** Frames the hub relayed that were not decodable images — counted rather than fatal. */
    public int undecodableFramesTotal() {
        return undecodableFramesTotal.get();
    }

    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("framesExaminedTotal", framesExaminedTotal());
        details.put("detectionsTotal", detectionsTotal());
        details.put("alertsSentTotal", alertsSentTotal());
        details.put("undecodableFramesTotal", undecodableFramesTotal());
        return details;
    }

    /**
     * Examines one frame from the stream. Never throws: the hub relays whatever a producer pushed
     * and does not require it to be an image, so a frame this cannot decode is counted and
     * skipped rather than allowed to end the subscription.
     */
    public void onFrame(Frame frame) {
        framesExaminedTotal.incrementAndGet();
        List<Detection> detections;
        try {
            detections = detector.detect(frame.bytes());
        } catch (RuntimeException exception) {
            undecodableFramesTotal.incrementAndGet();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("frameId", frame.frameId());
            metadata.put("source", frame.source());
            metadata.put("error", exception.getMessage());
            logger.debug("Skipped a frame that could not be decoded as an image.", metadata);
            return;
        }
        if (detections.isEmpty()) {
            return;
        }

        detectionsTotal.addAndGet(detections.size());
        if (!cooldowns.allow(frame.source(), Instant.now().getEpochSecond())) {
            return;
        }
        sendAlert(frame, detections);
    }

    private void sendAlert(Frame frame, List<Detection> detections) {
        // LinkedHashMap, not Map.of: frameIndex and capturedAt are optional and may be null,
        // which Map.of rejects with an NPE.
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("event", "person_detected");
        alert.put("source", frame.source());
        alert.put("frameId", frame.frameId());
        alert.put("frameIndex", frame.frameIndex());
        alert.put("timestamp", frame.capturedAt() == null ? null : frame.capturedAt().toString());
        alert.put("frameSha256", frame.sha256());
        alert.put("personCount", detections.size());
        alert.put("boxes", detections);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(alert)))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                alertsSentTotal.incrementAndGet();
                return;
            }
            alertFailed(frame, "alerting answered " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            alertFailed(frame, "interrupted");
        } catch (Exception exception) {
            alertFailed(frame, String.valueOf(exception.getMessage()));
        }
    }

    /**
     * A failed alert is logged rather than retried. Nothing is watching a response any more, so an
     * unreported detection would otherwise vanish silently — and retrying a stale detection into a
     * recovering alerting service is worse than saying it was missed.
     */
    private void alertFailed(Frame frame, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("frameId", frame.frameId());
        metadata.put("source", frame.source());
        metadata.put("reason", reason);
        logger.warn("Detected a person but could not deliver the alert.", metadata);
    }
}

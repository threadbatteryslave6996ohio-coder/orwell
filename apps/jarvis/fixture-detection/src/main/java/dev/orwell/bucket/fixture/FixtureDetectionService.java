package dev.orwell.bucket.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bucket.frame.client.Frame;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.Logger;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/**
 * Watches the hub's frames and reports when a fixture goes on or off.
 *
 * <p>A {@code FrameListener}, like person detection: producers push each frame once to the hub and
 * this subscribes, so adding it cost the cameras nothing and required no change to them.
 *
 * <p><strong>It reports transitions, and has no cooldown.</strong> That is the difference from
 * person detection, and it is deliberate. A person is an event, so repeated detections of the same
 * person need suppressing by a timer. A lamp has a <em>state</em>: it is either on or off, the
 * interesting moment is when that changes, and {@link FixtureWatch}'s hysteresis and confirmation
 * already make a change mean something. A timer on top would only delay real transitions.
 *
 * <p><strong>Cheap enough to be uninteresting.</strong> One JPEG decode per frame, shared by every
 * fixture on that source, then arithmetic over a few thousand pixels per region. A frame whose source
 * nothing watches is not even decoded. At 1 fps this is microseconds of a second — which is the
 * argument for measuring a region rather than asking a vision model whether the lamp looks on.
 *
 * <p>State changes are dated with the frame's own {@code capturedAt}, not with now. So a video
 * backfilled through {@code jarvis-frame-viewer} produces transitions dated when the footage was
 * recorded, which is the answer somebody reviewing an archive wants.
 */
public class FixtureDetectionService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /**
     * Sources whose latest frame is kept for the preview route. A ceiling because a fixture with no
     * {@code source} watches every one of them, and a producer can invent source names freely — one
     * frame each is ~40 KB, so this bounds the preview cache at a megabyte or so.
     */
    private static final int PREVIEW_SOURCES = 32;

    private final List<FixtureWatch> watches;
    private final String alertUrl;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Map<String, byte[]> latestFrames = new ConcurrentHashMap<>();
    private final AtomicLong framesExaminedTotal = new AtomicLong();
    private final AtomicLong framesSkippedTotal = new AtomicLong();
    private final AtomicLong undecodableFramesTotal = new AtomicLong();
    private final AtomicLong stateChangesTotal = new AtomicLong();
    private final AtomicLong alertsSentTotal = new AtomicLong();

    public FixtureDetectionService(List<Fixture> fixtures, String alertUrl, Logger logger) {
        Objects.requireNonNull(fixtures, "fixtures");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.alertUrl = alertUrl == null || alertUrl.isBlank() ? null : alertUrl.trim();
        this.watches = fixtures.stream().map(FixtureWatch::new).toList();
        this.httpClient = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), logger);
    }

    public List<FixtureWatch> watches() {
        return watches;
    }

    public Optional<FixtureWatch> watch(String name) {
        return watches.stream().filter(watch -> watch.fixture().name().equals(name)).findFirst();
    }

    /** The most recent frame from a source, for the preview overlay. */
    public Optional<byte[]> latestFrame(String source) {
        if (source != null) {
            // A named source answers with its own frame or with nothing. Falling back to "any
            // recent frame" here drew this fixture's region box over a *different* camera's
            // picture whenever its own camera was merely offline, and an operator calibrating
            // x/y/width/height against that preview is tuning against the wrong scene. An empty
            // answer says "that camera has sent nothing yet", which is the truth and is actionable.
            return Optional.ofNullable(latestFrames.get(source));
        }
        // Only a fixture with no source watches everything, so it has no source of its own to look
        // up — any frame seen recently is the right one to draw its region on.
        return latestFrames.values().stream().findFirst();
    }

    /**
     * Examines one frame. Never throws: the hub relays whatever a producer pushed and does not
     * require it to be an image, so a frame this cannot decode is counted and skipped rather than
     * allowed to end the subscription.
     */
    public void onFrame(Frame frame) {
        List<FixtureWatch> watching = watches.stream()
                .filter(watch -> watch.fixture().watches(frame.source()))
                .toList();
        if (watching.isEmpty()) {
            // Nothing here cares about this camera, so do not pay to decode it.
            framesSkippedTotal.incrementAndGet();
            return;
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(frame.bytes()));
        } catch (Exception exception) {
            image = null;
        }
        if (image == null) {
            undecodableFramesTotal.incrementAndGet();
            logger.debug("Skipped a frame that could not be decoded as an image.", Map.of(
                    "frameId", frame.frameId(), "source", String.valueOf(frame.source())));
            return;
        }

        framesExaminedTotal.incrementAndGet();
        rememberForPreview(frame);
        // The frame's own capture time, so a backfilled recording reports transitions when they
        // happened rather than when they were uploaded.
        Instant at = frame.capturedAt() == null ? Instant.now() : frame.capturedAt();

        for (FixtureWatch watch : watching) {
            double value;
            try {
                value = watch.fixture().measure(image);
            } catch (RuntimeException exception) {
                // A region that cannot be measured is a configuration problem, not a frame problem,
                // and it must not take the other fixtures down with it.
                logger.warn("Could not measure a fixture.", Map.of(
                        "fixture", watch.fixture().name(),
                        "error", exception.toString()));
                continue;
            }
            watch.update(value, frame.source(), at)
                    .ifPresent(change -> report(change, watch, frame));
        }
    }

    private void report(FixtureWatch.StateChange change, FixtureWatch watch, Frame frame) {
        stateChangesTotal.incrementAndGet();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fixture", change.fixture());
        metadata.put("source", change.source());
        metadata.put("state", change.to().label());
        metadata.put("previousState", change.from().label());
        metadata.put("value", change.value());
        metadata.put("onAbove", watch.fixture().onAbove());
        metadata.put("offBelow", watch.fixture().offBelow());
        metadata.put("frameId", frame.frameId());
        metadata.put("at", change.at().toString());
        // INFO, and one line per transition: this is the record, so it has to be greppable whether
        // or not anything is listening on the alert URL.
        logger.info("A fixture changed state.", metadata);

        if (alertUrl != null) {
            sendAlert(change, frame);
        }
    }

    private void sendAlert(FixtureWatch.StateChange change, Frame frame) {
        Map<String, Object> alert = new LinkedHashMap<>(change.asJson());
        alert.put("event", "fixture_state_changed");
        alert.put("frameId", frame.frameId());
        alert.put("frameSha256", frame.sha256());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                    // This runs on the frame listener, so an alerting service that accepts the
                    // request and then stalls would stop frames being consumed at all.
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(alert)))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                alertsSentTotal.incrementAndGet();
                return;
            }
            alertFailed(change, "alerting answered " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            alertFailed(change, "interrupted");
        } catch (Exception exception) {
            alertFailed(change, String.valueOf(exception.getMessage()));
        }
    }

    /**
     * A failed alert is logged, not retried. The transition is already in the log above, so nothing
     * is lost that mattered — and re-delivering a stale "the stove came on" into a recovering
     * alerting service is worse than a warning saying it was not delivered.
     */
    private void alertFailed(FixtureWatch.StateChange change, String reason) {
        logger.warn("A fixture changed state but the alert could not be delivered.", Map.of(
                "fixture", change.fixture(),
                "state", change.to().label(),
                "reason", reason));
    }

    private void rememberForPreview(Frame frame) {
        String source = frame.source() == null ? "" : frame.source();
        if (latestFrames.size() >= PREVIEW_SOURCES && !latestFrames.containsKey(source)) {
            return;
        }
        latestFrames.put(source, frame.bytes());
    }

    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fixtures", watches.size());
        details.put("framesExaminedTotal", framesExaminedTotal.get());
        // Frames from a camera no fixture watches. A large number here next to no examined frames
        // means the `source` on every fixture is wrong.
        details.put("framesSkippedTotal", framesSkippedTotal.get());
        details.put("undecodableFramesTotal", undecodableFramesTotal.get());
        details.put("stateChangesTotal", stateChangesTotal.get());
        details.put("alertsSentTotal", alertsSentTotal.get());
        Map<String, Object> states = new LinkedHashMap<>();
        for (FixtureWatch watch : watches) {
            states.put(watch.fixture().name(), watch.summaryJson());
        }
        details.put("states", states);
        return details;
    }
}

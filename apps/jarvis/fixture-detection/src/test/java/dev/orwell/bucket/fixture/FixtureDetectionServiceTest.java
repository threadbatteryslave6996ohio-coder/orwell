package dev.orwell.bucket.fixture;

import dev.orwell.bucket.frame.client.Frame;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The watcher end to end: real JPEG frames in, transitions out.
 *
 * <p>Encoded frames rather than raw images here, unlike {@link FixtureMetricTest}, because the
 * lossiness is part of what the thresholds have to survive — a detector tuned against uncompressed
 * blocks and deployed against a camera's JPEG is a detector tuned against the wrong signal.
 */
class FixtureDetectionServiceTest {
    private static final Instant AT = Instant.parse("2026-08-17T20:00:00Z");

    /** A lamp on the left of the frame, referenced against the right. Two frames to confirm. */
    private static List<Fixture> lamp() {
        return List.of(new Fixture("lamp", "cam1",
                new Region(0, 0, 0.4, 1), new Region(0.6, 0, 0.4, 1),
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 40, 15, 2));
    }

    @Test
    void reportsTheLampComingOnOnceItIsConfirmed() {
        List<String> logged = new ArrayList<>();
        FixtureDetectionService service =
                new FixtureDetectionService(lamp(), null, entry -> logged.add(entry.message()));

        // Dark: lamp region and reference both ~20, so the difference is ~0.
        service.onFrame(frame(1, "cam1", halves(20, 20), AT));
        service.onFrame(frame(2, "cam1", halves(20, 20), AT.plusSeconds(1)));
        assertThat(service.watch("lamp").orElseThrow().state()).isEqualTo(FixtureWatch.State.OFF);

        // Lamp on: its region jumps, the reference does not.
        service.onFrame(frame(3, "cam1", halves(150, 20), AT.plusSeconds(2)));
        assertThat(service.watch("lamp").orElseThrow().state()).isEqualTo(FixtureWatch.State.OFF);
        service.onFrame(frame(4, "cam1", halves(150, 20), AT.plusSeconds(3)));

        assertThat(service.watch("lamp").orElseThrow().state()).isEqualTo(FixtureWatch.State.ON);
        assertThat(logged).filteredOn("A fixture changed state."::equals).hasSize(2);
        assertThat(service.healthDetails()).containsEntry("stateChangesTotal", 2L);
    }

    /** Daylight lifting the whole scene must not read as the lamp coming on. */
    @Test
    void doesNotReportTheLampWhenTheWholeSceneBrightens() {
        FixtureDetectionService service = new FixtureDetectionService(lamp(), null, entry -> { });

        service.onFrame(frame(1, "cam1", halves(20, 20), AT));
        service.onFrame(frame(2, "cam1", halves(20, 20), AT.plusSeconds(1)));
        // Noon, lamp still off: both regions up by ~130.
        service.onFrame(frame(3, "cam1", halves(150, 150), AT.plusSeconds(2)));
        service.onFrame(frame(4, "cam1", halves(150, 150), AT.plusSeconds(3)));

        assertThat(service.watch("lamp").orElseThrow().state()).isEqualTo(FixtureWatch.State.OFF);
        assertThat(service.healthDetails()).containsEntry("stateChangesTotal", 1L);   // the initial off
    }

    /** A lit burner is coloured light; the kitchen lamp coming on over the same hob is not. */
    @Test
    void tellsALitBurnerFromTheKitchenLightComingOn() {
        List<Fixture> burner = List.of(new Fixture("burner", "cam1",
                new Region(0, 0, 0.4, 1), null,
                FixtureMetric.SATURATED_BRIGHT_FRACTION, 110, 0.35, 0.20, 0.05, 1));
        FixtureDetectionService service = new FixtureDetectionService(burner, null, entry -> { });

        // Hob washed with white light: bright, unsaturated.
        service.onFrame(frame(1, "cam1", colouredLeft(Color.WHITE), AT));
        assertThat(service.watch("burner").orElseThrow().state()).isEqualTo(FixtureWatch.State.OFF);

        // Flame: bright and strongly coloured. Blue, because a gas flame is.
        service.onFrame(frame(2, "cam1", colouredLeft(new Color(40, 120, 255)), AT.plusSeconds(1)));
        assertThat(service.watch("burner").orElseThrow().state()).isEqualTo(FixtureWatch.State.ON);
    }

    /** Frames from a camera nothing watches are not even decoded. */
    @Test
    void skipsFramesFromASourceNoFixtureWatches() {
        FixtureDetectionService service = new FixtureDetectionService(lamp(), null, entry -> { });

        service.onFrame(frame(1, "cam9", halves(150, 20), AT));

        assertThat(service.healthDetails())
                .containsEntry("framesSkippedTotal", 1L)
                .containsEntry("framesExaminedTotal", 0L);
        assertThat(service.watch("lamp").orElseThrow().state()).isEqualTo(FixtureWatch.State.UNKNOWN);
    }

    /** The hub does not require a frame to be an image, so this must be counted, not thrown. */
    @Test
    void countsAFrameItCannotDecodeAndCarriesOn() {
        FixtureDetectionService service = new FixtureDetectionService(lamp(), null, entry -> { });

        service.onFrame(frame(1, "cam1", new byte[] {1, 2, 3, 4}, AT));
        service.onFrame(frame(2, "cam1", halves(150, 20), AT.plusSeconds(1)));

        assertThat(service.healthDetails())
                .containsEntry("undecodableFramesTotal", 1L)
                .containsEntry("framesExaminedTotal", 1L);
    }

    /**
     * Transitions are dated with the frame's capture time, so a recording backfilled through
     * jarvis-frame-viewer reports when the stove was on, not when the video was uploaded.
     */
    @Test
    void datesTransitionsWithTheFramesOwnCaptureTime() {
        FixtureDetectionService service = new FixtureDetectionService(lamp(), null, entry -> { });
        Instant lastYear = Instant.parse("2025-01-02T03:04:05Z");

        service.onFrame(frame(1, "cam1", halves(150, 20), lastYear));
        service.onFrame(frame(2, "cam1", halves(150, 20), lastYear.plusSeconds(1)));

        assertThat(service.watch("lamp").orElseThrow().asJson())
                .containsEntry("lastChangeAt", lastYear.plusSeconds(1).toString());
    }

    @Test
    void postsAnAlertWhenAFixtureChangesState() throws Exception {
        try (JsonStubServer alerting = JsonStubServer.start()) {
            AtomicReference<String> received = alerting.stub("/alerts", 200, "{\"ok\":true}");
            FixtureDetectionService service = new FixtureDetectionService(
                    lamp(), alerting.baseUrl() + "/alerts", entry -> { });

            service.onFrame(frame(1, "cam1", halves(150, 20), AT));
            service.onFrame(frame(2, "cam1", halves(150, 20), AT.plusSeconds(1)));

            assertThat(received.get()).isNotNull();
            assertThat(received.get())
                    .contains("\"event\":\"fixture_state_changed\"")
                    .contains("\"fixture\":\"lamp\"")
                    .contains("\"state\":\"on\"")
                    .contains("\"previousState\":\"unknown\"");
            assertThat(service.healthDetails()).containsEntry("alertsSentTotal", 1L);
        }
    }

    /** An alerting service that is down costs a warning, not the subscription. */
    @Test
    void keepsDetectingWhenTheAlertCannotBeDelivered() {
        List<String> logged = new ArrayList<>();
        FixtureDetectionService service = new FixtureDetectionService(
                lamp(), "http://127.0.0.1:1/alerts", entry -> logged.add(entry.message()));

        service.onFrame(frame(1, "cam1", halves(150, 20), AT));
        service.onFrame(frame(2, "cam1", halves(150, 20), AT.plusSeconds(1)));

        assertThat(service.watch("lamp").orElseThrow().state()).isEqualTo(FixtureWatch.State.ON);
        // The transition is still in the log, which is what makes a failed alert survivable.
        assertThat(logged).contains("A fixture changed state.");
        assertThat(logged).anyMatch(message -> message.contains("could not be delivered"));
        assertThat(service.healthDetails()).containsEntry("alertsSentTotal", 0L);
    }

    /** The preview needs a frame to draw on, and draws the regions once one has arrived. */
    @Test
    void rendersAPreviewOfWhatItIsLookingAt() {
        FixtureDetectionService service = new FixtureDetectionService(lamp(), null, entry -> { });
        assertThat(service.latestFrame("cam1")).isEmpty();

        service.onFrame(frame(1, "cam1", halves(150, 20), AT));

        byte[] preview = FixturePreview
                .render(service.latestFrame("cam1").orElseThrow(),
                        service.watch("lamp").orElseThrow())
                .orElseThrow();
        assertThat(preview).isNotEmpty();
        // A real JPEG, so a browser renders it rather than downloading it.
        assertThat(preview[0] & 0xFF).isEqualTo(0xFF);
        assertThat(preview[1] & 0xFF).isEqualTo(0xD8);
    }

    /**
     * A named source with no frames yet answers with nothing, rather than with some other camera's
     * picture. Falling back to "any recent frame" drew this fixture's region box over a different
     * scene whenever its own camera was merely offline — and an operator calibrating x/y/width/height
     * against that preview would be tuning against the wrong room.
     */
    @Test
    void doesNotOfferAnotherCamerasFrameAsAFixturesPreview() {
        List<Fixture> twoCameras = List.of(
                new Fixture("lamp", "cam1", new Region(0, 0, 0.4, 1), null,
                        FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 40, 15, 2),
                new Fixture("hob", "cam2", new Region(0, 0, 0.4, 1), null,
                        FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 40, 15, 2));
        FixtureDetectionService service = new FixtureDetectionService(twoCameras, null, entry -> { });

        service.onFrame(frame(1, "cam1", halves(150, 20), AT));

        assertThat(service.latestFrame("cam1")).isPresent();
        assertThat(service.latestFrame("cam2")).isEmpty();
        // A fixture with no source of its own watches everything, so any recent frame is right for it.
        assertThat(service.latestFrame(null)).isPresent();
    }

    @Test
    void reportsEveryFixturesStateOnHealth() {
        FixtureDetectionService service = new FixtureDetectionService(lamp(), null, entry -> { });

        service.onFrame(frame(1, "cam1", halves(150, 20), AT));

        assertThat(service.healthDetails()).containsEntry("fixtures", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) service.healthDetails().get("states");
        assertThat(states).containsKey("lamp");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static Frame frame(long id, String source, byte[] bytes, Instant capturedAt) {
        return new Frame(id, source, id, capturedAt, "sha-" + id, bytes);
    }

    /** A JPEG whose left 40% is one grey and right 40% another, with black between them. */
    private static byte[] halves(int leftGrey, int rightGrey) {
        BufferedImage image = new BufferedImage(200, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(leftGrey, leftGrey, leftGrey));
        graphics.fillRect(0, 0, 80, 60);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(80, 0, 40, 60);
        graphics.setColor(new Color(rightGrey, rightGrey, rightGrey));
        graphics.fillRect(120, 0, 80, 60);
        graphics.dispose();
        return jpeg(image);
    }

    /** A JPEG whose left 40% is one colour — the hob — and the rest dark. */
    private static byte[] colouredLeft(Color colour) {
        BufferedImage image = new BufferedImage(200, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 200, 60);
        graphics.setColor(colour);
        graphics.fillRect(0, 0, 80, 60);
        graphics.dispose();
        return jpeg(image);
    }

    private static byte[] jpeg(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", out);
        } catch (Exception exception) {
            throw new IllegalStateException("could not encode a test frame", exception);
        }
        return out.toByteArray();
    }
}

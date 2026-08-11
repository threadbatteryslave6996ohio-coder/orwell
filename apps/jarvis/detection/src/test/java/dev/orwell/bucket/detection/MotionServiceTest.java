package dev.orwell.bucket.detection;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.orwell.bucket.detection.FrameTestFixtures.flat;
import static dev.orwell.bucket.detection.FrameTestFixtures.gradient;
import static dev.orwell.bucket.detection.FrameTestFixtures.request;
import static dev.orwell.bucket.detection.FrameTestFixtures.withBlock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionServiceTest {

    private final MotionService service = new MotionService(12, 0.02);

    @Test
    void firstFrameForASourceReportsNoChange() {
        Map<String, Object> response = service.motion(request("cam1", flat(100)));

        assertEquals(false, response.get("changed"));
        assertEquals(true, response.get("firstFrame"));
        assertEquals(0, response.get("changedCells"));
    }

    @Test
    void identicalFramesReportNoChange() {
        byte[] frame = flat(100);
        service.motion(request("cam1", frame));

        Map<String, Object> response = service.motion(request("cam1", frame));

        assertEquals(false, response.get("changed"));
        assertEquals(false, response.get("firstFrame"));
        assertEquals(0, response.get("changedCells"));
    }

    @Test
    void aBlockAppearingReportsAChange() {
        service.motion(request("cam1", flat(100)));

        Map<String, Object> response = service.motion(request("cam1", withBlock(100, 200)));

        assertEquals(true, response.get("changed"));
        assertTrue((int) response.get("changedCells") > 0);
        assertEquals(FrameChangeDetector.CELLS, response.get("totalCells"));
    }

    @Test
    void aBrightnessShiftUnderTheCellThresholdIsNotAChange() {
        service.motion(request("cam1", flat(100)));

        // Every cell moves, but only by 5 — below the 12 the detector treats as noise.
        Map<String, Object> response = service.motion(request("cam1", flat(105)));

        assertEquals(false, response.get("changed"));
        assertEquals(0, response.get("changedCells"));
    }

    @Test
    void aChangeTooSmallToClearTheFractionGateIsNotAChange() {
        MotionService strict = new MotionService(12, 0.5);
        strict.motion(request("cam1", flat(100)));

        // A quarter of the frame changes hard, but the gate wants half of it.
        Map<String, Object> response = strict.motion(request("cam1", withBlock(100, 200)));

        assertEquals(false, response.get("changed"));
        assertTrue((int) response.get("changedCells") > 0);
    }

    @Test
    void sourcesAreComparedIndependently() {
        service.motion(request("cam1", flat(100)));
        service.motion(request("cam2", flat(200)));

        // cam1's own history is flat(100), so its identical follow-up is unchanged even though
        // the most recent frame the service saw overall was a very different one from cam2.
        Map<String, Object> response = service.motion(request("cam1", flat(100)));

        assertEquals(false, response.get("changed"));
        assertEquals(false, response.get("firstFrame"));
    }

    @Test
    void countersTrackComparisonsRatherThanFrames() {
        service.motion(request("cam1", flat(100)));
        assertEquals(0, service.framesComparedTotal(), "a first frame is not a comparison");

        service.motion(request("cam1", withBlock(100, 200)));
        service.motion(request("cam1", withBlock(100, 200)));

        assertEquals(2, service.framesComparedTotal());
        assertEquals(1, service.changesDetectedTotal());
    }

    @Test
    void aResolutionChangeStillComparesAgainstTheSameScene() {
        service.motion(request("cam1", gradient(320, 240)));

        Map<String, Object> response = service.motion(request("cam1", gradient(640, 480)));

        assertEquals(false, response.get("changed"), "the fingerprint grid is resolution-independent");
    }

    @Test
    void aMissingFrameIsRejected() {
        FramePayload.InvalidFrameException exception = assertThrows(
                FramePayload.InvalidFrameException.class,
                () -> service.motion(new LinkedHashMap<>(Map.of("source", "cam1"))));

        assertEquals("frameBase64 missing", exception.getMessage());
    }

    @Test
    void aHashMismatchIsRejected() {
        Map<String, Object> payload = request("cam1", flat(100));
        payload.put("frameSha256", "0".repeat(64));

        assertThrows(FramePayload.InvalidFrameException.class, () -> service.motion(payload));
    }

    @Test
    void bytesThatAreNotAnImageAreRejected() {
        Map<String, Object> payload = request("cam1", new byte[] {1, 2, 3, 4});

        FramePayload.InvalidFrameException exception = assertThrows(
                FramePayload.InvalidFrameException.class, () -> service.motion(payload));

        assertEquals("unable to decode frame", exception.getMessage());
    }

    @Test
    void undecodableFramesMapToFourHundredRatherThanFiveHundred() {
        DetectionEndpoint endpoint = new DetectionEndpoint(
                new DetectionService("http://127.0.0.1:1/alerts", 60, 0.35), service);

        assertEquals(400, endpoint.motion(request("cam1", new byte[] {1, 2, 3, 4})).status());
    }

    @Test
    void theTrackedSourceCacheIsBounded() {
        // 64 is the cap; pushing well past it must not accumulate state without bound. The
        // eviction is observable: cam0 is long gone, so its next frame reads as a first frame.
        for (int index = 0; index < 200; index++) {
            service.motion(request("cam" + index, flat(100)));
        }

        assertEquals(true, service.motion(request("cam0", flat(100))).get("firstFrame"));
        assertFalse((boolean) service.motion(request("cam199", flat(100))).get("firstFrame"));
    }
}

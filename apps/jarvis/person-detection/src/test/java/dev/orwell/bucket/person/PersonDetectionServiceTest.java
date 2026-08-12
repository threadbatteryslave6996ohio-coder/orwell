package dev.orwell.bucket.person;

import com.sun.net.httpserver.HttpServer;
import dev.orwell.bucket.frame.client.Frame;
import dev.orwell.bucket.frame.FrameFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the service does with a frame off the stream.
 *
 * <p>The module had no tests at all before it became a stream consumer — every test the old
 * detection service carried was for frames, motion or retention. The two behaviours worth pinning
 * are the ones with no caller left to notice them: this runs on a shared reader thread with
 * nothing watching a response, so a frame it cannot decode must not end the subscription, and a
 * busy camera must not turn into an alert storm.
 */
class PersonDetectionServiceTest {
    private HttpServer alerting;
    private final List<String> alerts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startAlerting() throws Exception {
        alerting = HttpServer.create(new InetSocketAddress(0), 0);
        alerting.createContext("/alerts", exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                alerts.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        alerting.start();
    }

    @AfterEach
    void stopAlerting() {
        alerting.stop(0);
    }

    private PersonDetectionService service(int cooldownSeconds) {
        String url = "http://127.0.0.1:" + alerting.getAddress().getPort() + "/alerts";
        // minConfidence 0 so the heuristic detector reports on a synthetic frame.
        return new PersonDetectionService(url, cooldownSeconds, 0.0, entry -> { });
    }

    private static Frame frame(long id, byte[] bytes) {
        return new Frame(id, "cam1", id, Instant.parse("2026-08-11T12:00:00Z"), null, bytes);
    }

    @Test
    void aFrameThatIsNotAnImageIsCountedRatherThanThrown() {
        PersonDetectionService service = service(60);

        // The hub relays whatever a producer pushed and does not require it to be an image, so
        // this is a normal event, not an error. Throwing here would kill the subscription.
        service.onFrame(frame(1, new byte[] {1, 2, 3, 4}));

        assertEquals(1, service.framesExaminedTotal());
        assertEquals(1, service.undecodableFramesTotal());
        assertEquals(0, service.detectionsTotal());
        assertTrue(alerts.isEmpty());
    }

    @Test
    void everyFrameIsExaminedEvenWhenNothingIsFound() {
        PersonDetectionService service = service(60);

        for (long id = 1; id <= 3; id++) {
            service.onFrame(frame(id, FrameFixtures.flat(120)));
        }

        assertEquals(3, service.framesExaminedTotal());
        assertEquals(0, service.undecodableFramesTotal());
    }

    @Test
    void repeatDetectionsFromOneSourceAreCooledDownToASingleAlert() throws Exception {
        PersonDetectionService service = service(3600);
        byte[] withPerson = FrameFixtures.withBlock(40, 220);

        for (long id = 1; id <= 5; id++) {
            service.onFrame(frame(id, withPerson));
        }
        Thread.sleep(200);

        // Detections accumulate per frame; alerts do not. A camera watching a person stand still
        // would otherwise send one alert per frame, which at 5 fps is the storm this prevents.
        assertTrue(service.detectionsTotal() >= 5, "expected a detection per frame");
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).contains("\"event\":\"person_detected\""), alerts.get(0));
        assertTrue(alerts.get(0).contains("\"source\":\"cam1\""), alerts.get(0));
        // The frame id travels with the alert, so a viewer can be pointed at the exact frame.
        assertTrue(alerts.get(0).contains("\"frameId\":1"), alerts.get(0));
        assertEquals(1, service.alertsSentTotal());
    }

    @Test
    void anUnreachableAlertingServiceDoesNotStopDetection() throws Exception {
        // Nothing is listening on this port; the alert cannot be delivered.
        PersonDetectionService service =
                new PersonDetectionService("http://127.0.0.1:1/alerts", 3600, 0.0, entry -> { });

        service.onFrame(frame(1, FrameFixtures.withBlock(40, 220)));
        service.onFrame(frame(2, FrameFixtures.flat(120)));

        // The detection still counted, and the next frame was still examined — a failed alert is
        // logged, not fatal, because there is no caller left to hand the failure to.
        assertEquals(2, service.framesExaminedTotal());
        assertEquals(0, service.alertsSentTotal());
        assertTrue(service.detectionsTotal() >= 1);
    }

    @Test
    void healthReportsWhatDetectionHasDone() {
        PersonDetectionService service = service(60);
        service.onFrame(frame(1, new byte[] {9, 9, 9}));

        var details = service.healthDetails();

        assertEquals(1, details.get("framesExaminedTotal"));
        assertEquals(1, details.get("undecodableFramesTotal"));
        assertEquals(0, details.get("alertsSentTotal"));
    }
}

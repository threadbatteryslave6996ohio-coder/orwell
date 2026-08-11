package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That detection's scheduled sweep actually deletes.
 *
 * <p>This test exists because of a specific bug: the sweep used to be a package-private
 * {@code @Transactional} method invoked from another method on the same bean, so the Spring proxy
 * never advised it, the bulk delete threw "No active transaction", and the surrounding catch
 * turned that into a WARN once every interval. Retention looked healthy and ran never. Nothing
 * caught it because the schedule is turned off in the streaming tests and no test drove a sweep.
 */
@SpringBootTest
class FrameRetentionSweepTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void detectionProperties(DynamicPropertyRegistry registry) {
        registry.add("detection.alert-url", () -> "http://127.0.0.1:1/alerts");
        registry.add("detection.cooldown-seconds", () -> 60);
        registry.add("detection.min-confidence", () -> 0.35);
        registry.add("detection.motion.cell-threshold", () -> 12);
        registry.add("detection.motion.min-changed-fraction", () -> 0.02);
        registry.add("detection.stream.queue-depth", () -> 8);
        registry.add("detection.store.mode", () -> "async");
        registry.add("detection.store.queue-depth", () -> 512);
        registry.add("detection.frame-retention-seconds", () -> 60);
        registry.add("detection.frame-max-bytes", () -> 10L * FRAME_BYTES);
        // The schedule is parked; every test here drives the sweep itself.
        registry.add("detection.retention-sweep-seconds", () -> 3600);
    }

    private static final int FRAME_BYTES = 4096;

    @Autowired
    private FrameRetentionJob retention;
    @Autowired
    private FrameEventRepository events;

    @BeforeEach
    void clearStore() {
        events.deleteAll();
    }

    private void store(long id, Instant capturedAt) {
        events.save(new FrameEventEntity(
                id, "cam-retention", id, "0".repeat(64), capturedAt, new byte[FRAME_BYTES]));
    }

    @Test
    void theSweepDeletesFramesPastTheAgeBound() throws Exception {
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        for (long id = 1; id <= 5; id++) {
            store(id, old);
        }

        retention.sweep();

        assertThat(events.count()).isZero();
        assertThat(retention.framesDroppedByAgeTotal()).isEqualTo(5);
    }

    @Test
    void theSweepReportsNoErrorWhenItRuns() throws Exception {
        store(1, Instant.now());

        retention.sweep();

        // The bug this guards against surfaced as a caught exception, not a failure — so the
        // absence of an error is the assertion that matters.
        assertThat(retention.lastSweepError()).isNull();
    }

    @Test
    void framesInsideBothBoundsSurvive() throws Exception {
        for (long id = 1; id <= 5; id++) {
            store(id, Instant.now());
        }

        retention.sweep();

        assertThat(events.count()).isEqualTo(5);
        assertThat(retention.retainedBytes()).isEqualTo(5L * FRAME_BYTES);
    }

    @Test
    void theByteBudgetTrimsTheOldestFramesWhenNothingIsOldEnoughToAgeOut() throws Exception {
        // 20 fresh frames against a 10-frame budget: the age bound cannot help here.
        for (long id = 1; id <= 20; id++) {
            store(id, Instant.now());
        }

        retention.sweep();

        assertThat(retention.framesDroppedByAgeTotal()).isZero();
        assertThat(events.count()).isEqualTo(9);   // trimmed to 90% of the budget
        assertThat(retention.framesDroppedByBudgetTotal()).isEqualTo(11);
        // The survivors are the newest, so replay resumes at the oldest one still stored.
        assertThat(events.findTopByOrderByIdDesc().orElseThrow().getId()).isEqualTo(20L);
        assertThat(events.countBySource("cam-retention")).isEqualTo(9);
    }
}

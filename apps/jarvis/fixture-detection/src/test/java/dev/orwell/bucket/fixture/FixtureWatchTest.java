package dev.orwell.bucket.fixture;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a measurement becomes a state change.
 *
 * <p>The two behaviours worth having tests for are the two that make the difference between a usable
 * detector and 3,600 log lines an hour: the dead band between the thresholds, and the requirement
 * that agreeing frames be consecutive.
 */
class FixtureWatchTest {
    private static final Instant AT = Instant.parse("2026-08-17T20:00:00Z");

    /** onAbove 20, offBelow 10, so 10–20 is the dead band. Two frames to confirm. */
    private static FixtureWatch watch() {
        return new FixtureWatch(new Fixture("lamp", "cam1", new Region(0, 0, 1, 1), null,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 20, 10, 2));
    }

    @Test
    void staysUnknownUntilEnoughFramesAgree() {
        FixtureWatch watch = watch();

        assertThat(watch.update(30, "cam1", AT)).isEmpty();
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.UNKNOWN);

        Optional<FixtureWatch.StateChange> change = watch.update(30, "cam1", AT.plusSeconds(1));

        assertThat(change).isPresent();
        assertThat(change.get().from()).isEqualTo(FixtureWatch.State.UNKNOWN);
        assertThat(change.get().to()).isEqualTo(FixtureWatch.State.ON);
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.ON);
    }

    /** The first confirmed reading is reported: after a restart you want to be told the current state. */
    @Test
    void reportsTheInitialStateRatherThanWaitingForAChange() {
        FixtureWatch watch = watch();

        watch.update(1, "cam1", AT);
        Optional<FixtureWatch.StateChange> change = watch.update(1, "cam1", AT.plusSeconds(1));

        assertThat(change).isPresent();
        assertThat(change.get().to()).isEqualTo(FixtureWatch.State.OFF);
    }

    @Test
    void saysNothingWhileTheStateHolds() {
        FixtureWatch watch = onSince();

        assertThat(watch.update(30, "cam1", AT.plusSeconds(2))).isEmpty();
        assertThat(watch.update(99, "cam1", AT.plusSeconds(3))).isEmpty();
        assertThat(watch.update(21, "cam1", AT.plusSeconds(4))).isEmpty();
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.ON);
    }

    /**
     * The hysteresis. Dropping below the on-threshold is not enough to be off — it has to reach the
     * off-threshold. Without this a fixture sitting at 20 reports a change on every single frame.
     */
    @Test
    void holdsStateInsideTheDeadBand() {
        FixtureWatch watch = onSince();

        // 15 is below onAbove but above offBelow: not a candidate for either state.
        assertThat(watch.update(15, "cam1", AT.plusSeconds(2))).isEmpty();
        assertThat(watch.update(15, "cam1", AT.plusSeconds(3))).isEmpty();
        assertThat(watch.update(15, "cam1", AT.plusSeconds(4))).isEmpty();
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.ON);

        // Reaching offBelow does turn it off, once confirmed.
        assertThat(watch.update(9, "cam1", AT.plusSeconds(5))).isEmpty();
        assertThat(watch.update(9, "cam1", AT.plusSeconds(6))).isPresent();
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.OFF);
    }

    /**
     * Confirmation counts <em>consecutive</em> frames. One frame back the other side of the
     * threshold — a shadow, an auto-exposure wobble, a badly compressed frame — restarts the count
     * rather than pausing it, which is the difference between riding out an artefact and being
     * fooled by two of them a minute apart.
     */
    @Test
    void restartsTheCountWhenAFrameDisagrees() {
        FixtureWatch watch = onSince();

        assertThat(watch.update(5, "cam1", AT.plusSeconds(2))).isEmpty();   // 1 of 2 towards off
        assertThat(watch.update(30, "cam1", AT.plusSeconds(3))).isEmpty();  // back on: count resets
        assertThat(watch.update(5, "cam1", AT.plusSeconds(4))).isEmpty();   // 1 of 2 again
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.ON);

        assertThat(watch.update(5, "cam1", AT.plusSeconds(5))).isPresent(); // 2 of 2
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.OFF);
    }

    /** A reading inside the dead band also abandons a part-completed change. */
    @Test
    void abandonsAPendingChangeWhenAFrameLandsInTheDeadBand() {
        FixtureWatch watch = onSince();

        watch.update(5, "cam1", AT.plusSeconds(2));                          // 1 of 2 towards off
        assertThat(watch.update(15, "cam1", AT.plusSeconds(3))).isEmpty();   // dead band: resets
        assertThat(watch.update(5, "cam1", AT.plusSeconds(4))).isEmpty();    // 1 of 2, not 2 of 2
        assertThat(watch.state()).isEqualTo(FixtureWatch.State.ON);
    }

    /** confirmFrames = 1 is allowed, for a fixture whose reading is unambiguous. */
    @Test
    void reportsImmediatelyWhenOneFrameIsEnough() {
        FixtureWatch watch = new FixtureWatch(new Fixture("led", null, new Region(0, 0, 1, 1), null,
                FixtureMetric.BRIGHT_FRACTION, 200, 0.3, 0.01, 0.001, 1));

        assertThat(watch.update(0.5, "cam1", AT)).isPresent();
    }

    /** What calibration reads: the live value and the range seen, whatever the state machine did. */
    @Test
    void recordsTheRangeItHasSeenForCalibration() {
        FixtureWatch watch = watch();

        watch.update(4, "cam1", AT);
        watch.update(88, "cam1", AT.plusSeconds(1));
        watch.update(30, "cam1", AT.plusSeconds(2));

        assertThat(watch.asJson())
                .containsEntry("value", 30.0)
                .containsEntry("observedMin", 4.0)
                .containsEntry("observedMax", 88.0)
                .containsEntry("samples", 3L);
        assertThat((java.util.List<?>) watch.asJson().get("recent")).hasSize(3);
    }

    @Test
    void reportsNoReadingBeforeTheFirstFrame() {
        FixtureWatch watch = watch();

        assertThat(watch.asJson())
                .containsEntry("state", "unknown")
                .containsEntry("value", null)
                .containsEntry("observedMin", null);
    }

    @Test
    void countsTransitionsAndWhenTheLastOneWas() {
        FixtureWatch watch = onSince();

        watch.update(5, "cam1", AT.plusSeconds(2));
        watch.update(5, "cam1", AT.plusSeconds(3));

        assertThat(watch.asJson())
                .containsEntry("changesTotal", 2L)   // unknown -> on, then on -> off
                .containsEntry("lastChangeAt", AT.plusSeconds(3).toString())
                .containsEntry("state", "off");
    }

    /** A watch already confirmed ON, so the interesting assertions start from a known state. */
    private static FixtureWatch onSince() {
        FixtureWatch watch = watch();
        watch.update(30, "cam1", AT);
        watch.update(30, "cam1", AT.plusSeconds(1));
        return watch;
    }
}

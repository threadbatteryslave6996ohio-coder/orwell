package dev.orwell.health;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a failed check becomes an outage.
 *
 * <p>The behaviour worth testing is the restraint: a monitor that alerts on the first refused
 * connection gets muted, and a muted monitor protects nothing.
 */
class TargetWatchTest {
    private static final Instant AT = Instant.parse("2026-08-17T22:00:00Z");
    private static final HealthTarget TARGET =
            new HealthTarget("auth", URI.create("http://auth-server/auth/health"));

    /** Two failures to go down, one success to come back — the defaults. */
    private static TargetWatch watch() {
        return new TargetWatch(TARGET, 2, 1);
    }

    private static ProbeResult ok() {
        return ProbeResult.up(200, "healthy", 12);
    }

    private static ProbeResult bad() {
        return ProbeResult.down(0, null, 5000, "connection refused");
    }

    @Test
    void saysNothingAboutASingleFailedCheck() {
        TargetWatch watch = watch();
        watch.record(ok(), AT);

        assertThat(watch.record(bad(), AT.plusSeconds(30))).isEmpty();
        assertThat(watch.state()).isEqualTo(TargetWatch.State.UP);
    }

    @Test
    void reportsAnOutageOnTheSecondConsecutiveFailure() {
        TargetWatch watch = watch();
        watch.record(ok(), AT);
        watch.record(bad(), AT.plusSeconds(30));

        Optional<TargetWatch.StateChange> change = watch.record(bad(), AT.plusSeconds(60));

        assertThat(change).isPresent();
        assertThat(change.get().isOutage()).isTrue();
        assertThat(change.get().from()).isEqualTo(TargetWatch.State.UP);
        assertThat(change.get().to()).isEqualTo(TargetWatch.State.DOWN);
        assertThat(change.get().result().detail()).isEqualTo("connection refused");
    }

    /** A blip between two failures resets the count — the failures have to be consecutive. */
    @Test
    void doesNotAccumulateFailuresAcrossAGoodCheck() {
        TargetWatch watch = watch();
        watch.record(ok(), AT);

        watch.record(bad(), AT.plusSeconds(30));
        watch.record(ok(), AT.plusSeconds(60));
        assertThat(watch.record(bad(), AT.plusSeconds(90))).isEmpty();
        assertThat(watch.state()).isEqualTo(TargetWatch.State.UP);
    }

    @Test
    void saysNothingMoreWhileATargetStaysDown() {
        TargetWatch watch = down();

        assertThat(watch.record(bad(), AT.plusSeconds(90))).isEmpty();
        assertThat(watch.record(bad(), AT.plusSeconds(120))).isEmpty();
        assertThat(watch.state()).isEqualTo(TargetWatch.State.DOWN);
    }

    /** Recovery is news too: a monitor that only reports failure leaves you asking whether it is over. */
    @Test
    void reportsRecoveryOnTheFirstGoodCheck() {
        TargetWatch watch = down();

        Optional<TargetWatch.StateChange> change = watch.record(ok(), AT.plusSeconds(90));

        assertThat(change).isPresent();
        assertThat(change.get().isOutage()).isFalse();
        assertThat(change.get().to()).isEqualTo(TargetWatch.State.UP);
    }

    /**
     * And it carries how long the outage lasted. That figure is computed as the transition is made,
     * because the timestamp it comes from is the one the transition itself overwrites.
     */
    @Test
    void reportsHowLongTheOutageLasted() {
        TargetWatch watch = down();   // went down at AT+60

        TargetWatch.StateChange recovery = watch.record(ok(), AT.plusSeconds(240)).orElseThrow();

        assertThat(recovery.downFor()).isNotNull();
        assertThat(recovery.downFor().toSeconds()).isEqualTo(180);
        assertThat(recovery.asJson()).containsEntry("downForSeconds", 180L);
    }

    /** After a restart, what is already broken is reported rather than waiting for it to change. */
    @Test
    void reportsTheFirstConfirmedStateFromUnknown() {
        TargetWatch watch = watch();

        assertThat(watch.state()).isEqualTo(TargetWatch.State.UNKNOWN);
        TargetWatch.StateChange change = watch.record(ok(), AT).orElseThrow();

        assertThat(change.from()).isEqualTo(TargetWatch.State.UNKNOWN);
        assertThat(change.to()).isEqualTo(TargetWatch.State.UP);
    }

    @Test
    void countsChecksFailuresAndOutages() {
        TargetWatch watch = down();
        watch.record(ok(), AT.plusSeconds(90));

        assertThat(watch.asJson())
                .containsEntry("checksTotal", 4L)
                .containsEntry("failuresTotal", 2L)
                .containsEntry("outagesTotal", 1L)
                .containsEntry("state", "up")
                .containsEntry("consecutiveFailures", 0);
    }

    @Test
    void remembersTheSlowestCheckItHasSeen() {
        TargetWatch watch = watch();

        watch.record(ProbeResult.up(200, "healthy", 12), AT);
        watch.record(ProbeResult.up(200, "healthy", 950), AT.plusSeconds(30));
        watch.record(ProbeResult.up(200, "healthy", 30), AT.plusSeconds(60));

        assertThat(watch.asJson())
                .containsEntry("slowestMillis", 950L)
                .containsEntry("durationMillis", 30L);   // the last one, not the worst
    }

    /** A target confirmed UP and then DOWN at AT+60, for the tests that start from an outage. */
    private static TargetWatch down() {
        TargetWatch watch = watch();
        watch.record(ok(), AT);
        watch.record(bad(), AT.plusSeconds(30));
        watch.record(bad(), AT.plusSeconds(60));
        return watch;
    }
}

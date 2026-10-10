package dev.orwell.bucket.viewer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a video's frames are dated across the window the uploader named — the arithmetic the whole
 * feature turns on, and the one part of it that is wrong silently rather than loudly.
 */
class UploadJobTest {
    private static final Instant TEN = Instant.parse("2026-08-17T10:00:00Z");
    private static final Instant FIVE_PAST = Instant.parse("2026-08-17T10:05:00Z");

    @Test
    void spreadsFramesEvenlyFromTheStartOfTheWindow() {
        UploadJob job = job(TEN, FIVE_PAST);

        // Five minutes, five frames: one a minute, the first on the lower bound.
        assertThat(job.timestampFor(0, 5)).isEqualTo(TEN);
        assertThat(job.timestampFor(1, 5)).isEqualTo(Instant.parse("2026-08-17T10:01:00Z"));
        assertThat(job.timestampFor(4, 5)).isEqualTo(Instant.parse("2026-08-17T10:04:00Z"));
    }

    /**
     * The last frame lands inside the window, never on its upper bound — because every time window in
     * the repo is half-open, so a frame dated exactly {@code to} would be excluded from a
     * {@code GET /frames?from=…&to=…} using the same two bounds the upload was given.
     */
    @Test
    void keepsEveryFrameInsideTheHalfOpenWindow() {
        UploadJob job = job(TEN, FIVE_PAST);

        assertThat(job.timestampFor(0, 300)).isEqualTo(TEN);
        assertThat(job.timestampFor(299, 300)).isBefore(FIVE_PAST);
        assertThat(job.timestampFor(299, 300)).isEqualTo(Instant.parse("2026-08-17T10:04:59Z"));
    }

    /**
     * Sub-second spacing has to survive. A five-second window at 20 frames is 250 ms a frame, and
     * integer-second arithmetic would put all twenty on the lower bound — one visible moment of
     * footage collapsed into one timestamp.
     */
    @Test
    void keepsSubSecondSpacingOnAShortWindow() {
        UploadJob job = job(TEN, TEN.plusSeconds(5));

        assertThat(job.timestampFor(1, 20)).isEqualTo(Instant.parse("2026-08-17T10:00:00.250Z"));
        assertThat(job.timestampFor(19, 20)).isEqualTo(Instant.parse("2026-08-17T10:00:04.750Z"));
    }

    /** A one-frame video has nowhere to spread to, and lands at the start of its window. */
    @Test
    void datesASingleFrameAtTheStartOfTheWindow() {
        assertThat(job(TEN, FIVE_PAST).timestampFor(0, 1)).isEqualTo(TEN);
    }

    /** Progress is reported from what the hub accepted, and remembers the id range it created. */
    @Test
    void tracksProgressAndTheIdRangeItCreated() {
        UploadJob job = job(TEN, FIVE_PAST);
        assertThat(job.status()).isEqualTo(UploadJob.Status.QUEUED);

        job.extracting();
        job.pushing(3);
        job.pushed(91);
        job.pushed(92);
        job.failedFrame();
        job.done();

        assertThat(job.status()).isEqualTo(UploadJob.Status.DONE);
        assertThat(job.asJson())
                .containsEntry("framesTotal", 3)
                .containsEntry("framesPushed", 2L)
                .containsEntry("framesFailed", 1L)
                .containsEntry("firstFrameId", 91L)
                .containsEntry("lastFrameId", 92L)
                .containsEntry("status", "done");
    }

    @Test
    void reportsWhyItFailed() {
        UploadJob job = job(TEN, FIVE_PAST);

        job.failed("no frames came out of that file");

        assertThat(job.asJson())
                .containsEntry("status", "failed")
                .containsEntry("error", "no frames came out of that file");
    }

    private static UploadJob job(Instant from, Instant to) {
        return new UploadJob("1-abcdef12", "cam1", from, to, 1.0, 1024, TEN);
    }
}

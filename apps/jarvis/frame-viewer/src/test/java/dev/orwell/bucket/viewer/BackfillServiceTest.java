package dev.orwell.bucket.viewer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job bookkeeping around a backfill.
 *
 * <p>Extraction is made to fail here on purpose — the subject is the id and the ordering, which are
 * what {@code recentJobs()} and the eviction of old jobs both rest on, and neither depends on a video
 * actually being cut.
 */
class BackfillServiceTest {
    private static final Instant FROM = Instant.parse("2026-08-17T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-17T10:05:00Z");

    /**
     * Upload ids sort lexically, because that is how they are ordered and evicted — so they have to be
     * zero-padded. Unpadded, {@code "10-…"} sorts before {@code "9-…"}: past nine uploads the newest
     * job was listed in the middle of the page instead of at the top, and past the history limit it
     * was the one evicted while much older jobs survived and its status poll started answering 404.
     */
    @Test
    void listsJobsNewestFirstPastTheNinthUpload() throws Exception {
        BackfillService backfill = new BackfillService(
                // A path that cannot be executed, so every job fails immediately. The ids are what
                // this test is about.
                new VideoFrames("/nonexistent/ffmpeg", entry -> { }),
                "http://127.0.0.1:1", 1.0, 25, entry -> { });

        List<String> accepted = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            Path video = Files.createTempFile("backfill-test-", ".video");
            Files.writeString(video, "not a video");
            accepted.add(backfill.accept(video, "cam1", FROM, TO, 1.0, 11).id());
        }

        List<String> listed = backfill.recentJobs().stream().map(UploadJob::id).toList();

        assertThat(listed).hasSize(12);
        // Newest first: the last id accepted is the first one listed.
        assertThat(listed.get(0)).isEqualTo(accepted.get(11));
        assertThat(listed.get(11)).isEqualTo(accepted.get(0));
        // And the ordering really is the reverse of acceptance, not just at the ends.
        assertThat(listed).isEqualTo(accepted.reversed());
    }

    @Test
    void findsAJobByTheIdItHandedOut() throws Exception {
        BackfillService backfill = new BackfillService(
                new VideoFrames("/nonexistent/ffmpeg", entry -> { }),
                "http://127.0.0.1:1", 1.0, 25, entry -> { });
        Path video = Files.createTempFile("backfill-test-", ".video");
        Files.writeString(video, "not a video");

        UploadJob job = backfill.accept(video, "cam1", FROM, TO, 2.0, 11);

        assertThat(backfill.job(job.id())).isPresent();
        assertThat(backfill.job("no-such-upload")).isEmpty();
        assertThat(job.asJson())
                .containsEntry("source", "cam1")
                .containsEntry("fps", 2.0)
                .containsEntry("videoBytes", 11L);
    }

    /** No hub configured is what makes the upload route answer 503 rather than accepting silently. */
    @Test
    void reportsWhetherAHubIsConfigured() {
        VideoFrames videos = new VideoFrames("ffmpeg", entry -> { });

        assertThat(new BackfillService(videos, "", 1.0, 25, entry -> { }).configured()).isFalse();
        assertThat(new BackfillService(videos, "   ", 1.0, 25, entry -> { }).configured()).isFalse();
        BackfillService configured =
                new BackfillService(videos, "http://hub:9001/", 1.0, 25, entry -> { });
        assertThat(configured.configured()).isTrue();
        // The trailing slash is trimmed, so the pushes go to /frames and not //frames.
        assertThat(configured.hubUrl()).isEqualTo("http://hub:9001");
    }
}

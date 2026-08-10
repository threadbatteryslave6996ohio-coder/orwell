package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.InstagramPost;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a post is split across its three tables, and — the bit worth testing — when the metric series
 * grows and when it merely gets its timestamp bumped.
 */
class PostWriterTest extends GraphTest {
    private static final String ME = "1000";

    @Test
    void splitsAPostAcrossIdentityCaptionAndMetrics() throws Exception {
        writer().record(ME, List.of(post("p1", "Hello", 10L, 2L)), Instant.now());

        assertThat(strings("SELECT id FROM post")).containsExactly("p1");
        assertThat(strings("SELECT account_id FROM post")).containsExactly(ME);
        assertThat(strings("SELECT caption FROM post_caption")).containsExactly("Hello");
        assertThat(strings("SELECT likes_count::text FROM post_metric")).containsExactly("10");
    }

    @Test
    void skipsAPostWithNoId() throws Exception {
        int recorded = writer().record(
                ME, List.of(new InstagramPost(null, "abc", "x", null, null, 1L, 1L, null, null, null)),
                Instant.now());

        assertThat(recorded).isZero();
        assertThat(count("post")).isZero();
    }

    /** A post nobody interacted with should cost a timestamp update, not a row a day forever. */
    @Test
    void doesNotGrowTheSeriesWhenNothingChanged() throws Exception {
        Instant first = Instant.now().minus(1, ChronoUnit.DAYS);
        writer().record(ME, List.of(post("p1", "Hello", 10L, 2L)), first);

        Instant second = Instant.now();
        writer().record(ME, List.of(post("p1", "Hello", 10L, 2L)), second);

        assertThat(count("post_metric")).isEqualTo(1);
        assertThat(instant("SELECT first_seen_at FROM post_metric"))
                .isCloseTo(first, within());
        assertThat(instant("SELECT last_seen_at FROM post_metric"))
                .isCloseTo(second, within());
    }

    @Test
    void appendsToTheSeriesWhenANumberMoves() throws Exception {
        writer().record(ME, List.of(post("p1", "Hello", 10L, 2L)),
                Instant.now().minus(1, ChronoUnit.DAYS));

        writer().record(ME, List.of(post("p1", "Hello", 25L, 2L)), Instant.now());

        assertThat(strings("SELECT likes_count::text FROM post_metric ORDER BY first_seen_at"))
                .containsExactly("10", "25");
    }

    /** Captions can be edited, so they behave like bios: both versions are kept. */
    @Test
    void keepsBothCaptionsWhenOneIsEdited() throws Exception {
        writer().record(ME, List.of(post("p1", "First wording", 1L, 0L)),
                Instant.now().minus(1, ChronoUnit.DAYS));

        writer().record(ME, List.of(post("p1", "Second wording", 1L, 0L)), Instant.now());

        assertThat(strings("SELECT caption FROM post_caption ORDER BY last_seen_at"))
                .containsExactly("First wording", "Second wording");
        assertThat(count("post")).isEqualTo(1);
    }

    @Test
    void writesNoCaptionRowWhenThePostHasNone() throws Exception {
        writer().record(ME, List.of(post("p1", null, 1L, 0L)), Instant.now());

        assertThat(count("post")).isEqualTo(1);
        assertThat(count("post_caption")).isZero();
    }

    /**
     * {@code latestPosts} is only the newest twelve, so absence from it says nothing. Marking the
     * rest deleted would retire an account's whole back catalogue on every sync.
     */
    @Test
    void neverMarksAPostDeletedFromATruncatedListing() throws Exception {
        writer().record(ME, List.of(post("p1", "old", 1L, 0L), post("p2", "older", 1L, 0L)),
                Instant.now().minus(1, ChronoUnit.DAYS));

        writer().record(ME, List.of(post("p1", "old", 1L, 0L)), Instant.now());

        assertThat(strings("SELECT id FROM post WHERE deleted_at IS NULL ORDER BY id"))
                .containsExactly("p1", "p2");
    }

    @Test
    void recordsSeveralPostsInOneGo() throws Exception {
        int recorded = writer().record(ME, List.of(
                post("p1", "one", 1L, 0L), post("p2", "two", 2L, 0L), post("p3", "three", 3L, 0L)),
                Instant.now());

        assertThat(recorded).isEqualTo(3);
        assertThat(count("post")).isEqualTo(3);
        assertThat(count("post_metric")).isEqualTo(3);
    }

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return org.assertj.core.api.Assertions.within(2, ChronoUnit.SECONDS);
    }

    private PostWriter writer() throws Exception {
        try (var statement = connection.prepareStatement(
                "INSERT INTO account (id, added) VALUES (?, now()) ON CONFLICT DO NOTHING")) {
            statement.setString(1, ME);
            statement.executeUpdate();
        }
        return new PostWriter(connection, url -> {
            throw new UnsupportedOperationException("no media in this test");
        }, new DisabledPictureStore(), NO_OP_LOGGER);
    }

    private static InstagramPost post(String id, String caption, Long likes, Long comments) {
        return new InstagramPost(id, "code-" + id, caption, Instant.now().minus(2, ChronoUnit.DAYS),
                "Image", likes, comments, null, null, "https://instagram.com/p/" + id);
    }
}

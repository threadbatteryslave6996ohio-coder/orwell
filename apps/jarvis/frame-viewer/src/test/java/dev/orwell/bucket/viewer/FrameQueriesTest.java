package dev.orwell.bucket.viewer;

import dev.orwell.testing.PostgresJdbcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the viewer reads. Read-only, so these are about shape and scope rather than effects.
 *
 * <p>The table is created by hand here rather than by Hibernate, because the hub owns the schema and
 * this app only reads it — standing it up from the entity would test that Hibernate agrees with
 * itself. The DDL below is the shape {@code \d frame_events} prints against a running stack,
 * {@code captured_at} being {@code timestamp with time zone} included; that column's type is the one
 * detail a reader can get wrong without failing.
 */
class FrameQueriesTest extends PostgresJdbcTest {
    private static final Instant NOON = Instant.parse("2026-08-17T12:00:00Z");

    private Connection connection;

    @BeforeEach
    void createTable() throws SQLException {
        execute("DROP TABLE IF EXISTS frame_events",
                "CREATE TABLE frame_events ("
                        + "id bigint PRIMARY KEY,"
                        + "source varchar(255) NOT NULL,"
                        + "frame_index bigint,"
                        + "sha256 varchar(64) NOT NULL,"
                        + "captured_at timestamp(6) with time zone NOT NULL,"
                        + "frame_bytes bytea NOT NULL)");
        connection = connection();
    }

    /** Two cameras, interleaved ids — which is what a shared id sequence actually produces. */
    private void given() throws SQLException {
        store(10, "cam1", 1L, NOON, 1000);
        store(11, "cam2", 1L, NOON.plusSeconds(1), 2000);
        store(12, "cam1", 2L, NOON.plusSeconds(2), 1000);
        store(13, "cam1", null, NOON.plusSeconds(3), 1500);
        store(14, "cam2", 2L, NOON.plusSeconds(4), 2000);
    }

    @Test
    void listsEveryDeviceThatHasFramesStored() throws Exception {
        given();

        List<FrameSource> sources = FrameQueries.sources(connection);

        // Most recent activity first, which is the order the picker wants.
        assertThat(sources).extracting(FrameSource::source).containsExactly("cam2", "cam1");
        FrameSource cam1 = sources.get(1);
        assertThat(cam1.frames()).isEqualTo(3);
        assertThat(cam1.oldestId()).isEqualTo(10);
        assertThat(cam1.newestId()).isEqualTo(13);
        assertThat(cam1.oldestCapturedAt()).isEqualTo(NOON);
        assertThat(cam1.newestCapturedAt()).isEqualTo(NOON.plusSeconds(3));
    }

    @Test
    void opensOnTheNewestFrameOfTheChosenDevice() throws Exception {
        given();

        assertThat(FrameQueries.latest(connection, "cam1")).get()
                .extracting(StoredFrame::frameId).isEqualTo(13L);
        assertThat(FrameQueries.latest(connection, "cam2")).get()
                .extracting(StoredFrame::frameId).isEqualTo(14L);
        // No source named: the newest frame from any camera.
        assertThat(FrameQueries.latest(connection, null)).get()
                .extracting(StoredFrame::frameId).isEqualTo(14L);
    }

    /** The "back" button, repeatedly — and the end of the window rather than a wrap-around. */
    @Test
    void stepsBackThroughOneDeviceSkippingTheOthers() throws Exception {
        given();

        Optional<StoredFrame> step = FrameQueries.latest(connection, "cam1");
        assertThat(step).get().extracting(StoredFrame::frameId).isEqualTo(13L);

        step = FrameQueries.older(connection, "cam1", step.get().frameId());
        assertThat(step).get().extracting(StoredFrame::frameId).isEqualTo(12L);

        step = FrameQueries.older(connection, "cam1", step.get().frameId());
        assertThat(step).get().extracting(StoredFrame::frameId).isEqualTo(10L);   // 11 is cam2's

        assertThat(FrameQueries.older(connection, "cam1", 10L)).isEmpty();
    }

    @Test
    void stepsForwardAgainToWhereItStarted() throws Exception {
        given();

        assertThat(FrameQueries.newer(connection, "cam1", 10L)).get()
                .extracting(StoredFrame::frameId).isEqualTo(12L);
        assertThat(FrameQueries.newer(connection, "cam1", 13L)).isEmpty();
    }

    /** Unscoped, a step crosses cameras in id order — which is what "all sources" means. */
    @Test
    void stepsAcrossEveryDeviceWhenNoSourceIsChosen() throws Exception {
        given();

        assertThat(FrameQueries.older(connection, null, 12L)).get()
                .extracting(StoredFrame::frameId).isEqualTo(11L);
        assertThat(FrameQueries.oldest(connection, null)).get()
                .extracting(StoredFrame::frameId).isEqualTo(10L);
    }

    @Test
    void findsTheOldestFrameStillStored() throws Exception {
        given();

        assertThat(FrameQueries.oldest(connection, "cam1")).get()
                .extracting(StoredFrame::frameId).isEqualTo(10L);
    }

    @Test
    void readsOneFrameById() throws Exception {
        given();

        StoredFrame frame = FrameQueries.byId(connection, 12L).orElseThrow();

        assertThat(frame.source()).isEqualTo("cam1");
        assertThat(frame.frameIndex()).isEqualTo(2L);
        assertThat(frame.capturedAt()).isEqualTo(NOON.plusSeconds(2));
        assertThat(frame.bytes()).isEqualTo(1000);
        assertThat(frame.sha256()).isEqualTo("sha-12");
        assertThat(FrameQueries.byId(connection, 999L)).isEmpty();
    }

    /**
     * A producer need not send {@code frameIndex}, and {@code getLong} answers 0 for a NULL — so
     * without care the frame counter of a producer that sends none reads as frame 0.
     */
    @Test
    void reportsAnAbsentFrameIndexAsAbsentRatherThanZero() throws Exception {
        given();

        assertThat(FrameQueries.byId(connection, 13L).orElseThrow().frameIndex()).isNull();
        assertThat(FrameQueries.byId(connection, 10L).orElseThrow().frameIndex()).isEqualTo(1L);
    }

    /**
     * Counted, not derived from the ids: they come from a sequence shared by every source, so the
     * gap between two of one camera's ids is not the number of its frames between them.
     */
    @Test
    void countsPositionWithinTheDeviceRatherThanFromTheIds() throws Exception {
        given();

        assertThat(FrameQueries.total(connection, "cam1")).isEqualTo(3);
        assertThat(FrameQueries.positionOf(connection, "cam1", 10L)).isEqualTo(1);
        assertThat(FrameQueries.positionOf(connection, "cam1", 12L)).isEqualTo(2);
        assertThat(FrameQueries.positionOf(connection, "cam1", 13L)).isEqualTo(3);
        assertThat(FrameQueries.total(connection, null)).isEqualTo(5);
    }

    @Test
    void pagesTheListNewestFirstFromAnExclusiveCursor() throws Exception {
        given();

        List<StoredFrame> first = FrameQueries.page(connection, "cam1", null, 2);
        assertThat(first).extracting(StoredFrame::frameId).containsExactly(13L, 12L);

        List<StoredFrame> next = FrameQueries.page(connection, "cam1", 12L, 2);
        assertThat(next).extracting(StoredFrame::frameId).containsExactly(10L);
    }

    @Test
    void readsTheFrameBytesOnlyWhenAsked() throws Exception {
        given();

        assertThat(FrameQueries.imageBytes(connection, 11L)).get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY)
                .hasSize(2000);
        assertThat(FrameQueries.imageBytes(connection, 999L)).isEmpty();
    }

    /** The size reported by the metadata queries is the stored payload's, not a guess. */
    @Test
    void reportsEachFramesStoredSize() throws Exception {
        given();

        assertThat(FrameQueries.byId(connection, 11L).orElseThrow().bytes()).isEqualTo(2000);
        assertThat(FrameQueries.storedFrames(connection)).isEqualTo(5);
    }

    @Test
    void answersEmptyRatherThanFailingOnAnEmptyTable() throws Exception {
        assertThat(FrameQueries.sources(connection)).isEmpty();
        assertThat(FrameQueries.latest(connection, null)).isEmpty();
        assertThat(FrameQueries.latest(connection, "cam1")).isEmpty();
        assertThat(FrameQueries.total(connection, "cam1")).isZero();
        assertThat(FrameQueries.page(connection, null, null, 10)).isEmpty();
    }

    private void store(long id, String source, Long frameIndex, Instant capturedAt, int bytes)
            throws SQLException {
        String sql = "INSERT INTO frame_events"
                + " (id, source, frame_index, sha256, captured_at, frame_bytes)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, source);
            if (frameIndex == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, frameIndex);
            }
            statement.setString(4, "sha-" + id);
            statement.setTimestamp(5, Timestamp.from(capturedAt));
            statement.setBytes(6, new byte[bytes]);
            statement.executeUpdate();
        }
    }
}

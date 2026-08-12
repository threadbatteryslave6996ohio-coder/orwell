package dev.orwell.bucket.retention.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the worker's scheduled sweep actually deletes from {@code frame_events}.
 *
 * <p>This test exists because of a specific bug in the version of this code that lived inside the
 * detection service: the sweep was a package-private {@code @Transactional} method invoked from
 * another method on the same bean, so the Spring proxy never advised it, the bulk delete threw
 * "No active transaction", and the surrounding catch turned that into a WARN once every interval.
 * Retention looked healthy and ran never. The worker has no Spring proxy to be defeated by, which
 * removes that failure mode rather than testing around it — but the assertion that a sweep leaves
 * no error behind is kept, because that is the shape the bug took.
 *
 * <p>Plain JDBC against a real Postgres, matching {@code RetentionSweeperTest} in the library: the
 * worker has no JPA and no entity classes, so the table is created here as the hub's Hibernate
 * mapping would create it.
 */
@Testcontainers
class FrameRetentionSweepTest {
    private static final int FRAME_BYTES = 4096;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DataSource dataSource;
    private FrameRetentionSchedule schedule;

    /** The columns of frame_events the sweep touches, as jarvis-hub's entity maps them. */
    @BeforeEach
    void createTable() throws SQLException {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS frame_events");
            statement.execute("""
                    CREATE TABLE frame_events (
                        id BIGINT PRIMARY KEY,
                        source TEXT NOT NULL,
                        frame_index BIGINT,
                        sha256 TEXT NOT NULL,
                        captured_at TIMESTAMP NOT NULL,
                        frame_bytes BYTEA NOT NULL
                    )""");
        }
        // A 10-frame byte budget and a 60s age bound, with the schedule never firing on its own:
        // every test here drives the sweep itself.
        schedule = new FrameRetentionSchedule(
                dataSource, (entry) -> { }, 10L * FRAME_BYTES, 60, 3600);
    }

    private void store(long id, Instant capturedAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO frame_events (id, source, frame_index, sha256, captured_at,"
                                + " frame_bytes) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, "cam-retention");
            statement.setLong(3, id);
            statement.setString(4, "0".repeat(64));
            statement.setTimestamp(5, Timestamp.from(capturedAt));
            statement.setBytes(6, new byte[FRAME_BYTES]);
            statement.executeUpdate();
        }
    }

    private long count() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM frame_events")) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    private long highestId() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT MAX(id) FROM frame_events")) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    @Test
    void theSweepDeletesFramesPastTheAgeBound() throws Exception {
        Instant old = Instant.now().minus(10, ChronoUnit.MINUTES);
        for (long id = 1; id <= 5; id++) {
            store(id, old);
        }

        schedule.sweep();

        assertEquals(0, count());
        assertEquals(5, schedule.framesDroppedByAgeTotal());
    }

    @Test
    void theSweepReportsNoErrorWhenItRuns() throws Exception {
        store(1, Instant.now());

        schedule.sweep();

        // The bug this guards against surfaced as a caught exception, not a failure — so the
        // absence of an error is the assertion that matters.
        assertNull(schedule.lastSweepError());
    }

    @Test
    void framesInsideBothBoundsSurvive() throws Exception {
        for (long id = 1; id <= 5; id++) {
            store(id, Instant.now());
        }

        schedule.sweep();

        assertEquals(5, count());
        assertEquals(5L * FRAME_BYTES, schedule.retainedBytes());
    }

    @Test
    void theByteBudgetTrimsTheOldestFramesWhenNothingIsOldEnoughToAgeOut() throws Exception {
        // 20 fresh frames against a 10-frame budget: the age bound cannot help here.
        for (long id = 1; id <= 20; id++) {
            store(id, Instant.now());
        }

        schedule.sweep();

        assertEquals(0, schedule.framesDroppedByAgeTotal());
        assertEquals(9, count());   // trimmed to 90% of the budget
        assertEquals(11, schedule.framesDroppedByBudgetTotal());
        // The survivors are the newest, so replay resumes at the oldest one still stored.
        assertEquals(20L, highestId());
    }

    /**
     * The failure the worker exists to survive: a sweep that throws must not cancel the schedule,
     * and must leave the error where an operator can find it. Inside the old detection service the
     * equivalent path silently stopped retention while the process stayed up and healthy.
     */
    @Test
    void aFailedSweepIsRecordedRatherThanThrown() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE frame_events");
        }

        assertNull(schedule.lastSweepError());
        schedule.scheduledSweep();

        assertNotNull(schedule.lastSweepError());
        assertTrue(schedule.lastSweepError().contains("frame_events"),
                schedule.lastSweepError());
    }
}

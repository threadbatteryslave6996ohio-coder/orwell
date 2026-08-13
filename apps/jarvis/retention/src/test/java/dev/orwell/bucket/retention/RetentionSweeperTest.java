package dev.orwell.bucket.retention;

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
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweeper against a real Postgres, because every interesting behaviour here is a property of
 * the database rather than of the Java: what {@code octet_length} costs on a TOASTed column,
 * whether {@code DELETE ... RETURNING} reports what it removed, and — the one that motivated the
 * design — that deleting rows does not shrink {@code pg_total_relation_size}.
 */
@Testcontainers
class RetentionSweeperTest {
    private static final int FRAME_BYTES = 40 * 1024;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DataSource dataSource;
    private RetentionSweeper sweeper;

    private static RetentionPolicy policy(Long maxBytes, Duration maxAge) {
        return RetentionPolicy.of("frames", "id", "captured_at", "payload", maxBytes, maxAge);
    }

    @BeforeEach
    void createTable() throws SQLException {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
        sweeper = new RetentionSweeper(dataSource, entry -> { });

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS frames");
            statement.execute("""
                    CREATE TABLE frames (
                        id          bigint PRIMARY KEY,
                        captured_at timestamptz NOT NULL,
                        payload     bytea NOT NULL
                    )""");
            // Matches production: already-compressed frames, stored out of line without a
            // pointless compression attempt.
            statement.execute("ALTER TABLE frames ALTER COLUMN payload SET STORAGE EXTERNAL");
        }
    }

    /** Inserts {@code count} frames of {@link #FRAME_BYTES} each, ids 1..count, oldest first. */
    private void insertFrames(int count, Instant newest) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO frames (id, captured_at, payload) VALUES (?, ?, ?)")) {
            for (int index = 0; index < count; index++) {
                statement.setLong(1, index + 1L);
                // Oldest first: id 1 is the furthest back, one second apart.
                statement.setTimestamp(2,
                        Timestamp.from(newest.minusSeconds(count - 1L - index)));
                statement.setBytes(3, new byte[FRAME_BYTES]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private long rowCount() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM frames")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private long lowestId() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COALESCE(MIN(id), 0) FROM frames")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // --- byte budget --------------------------------------------------------------------------

    @Test
    void aTableUnderBudgetIsLeftAlone() throws Exception {
        insertFrames(10, Instant.now());

        SweepResult result = sweeper.sweep(policy(100L * 1024 * 1024, null), Instant.now());

        assertEquals(0, result.rowsDropped());
        assertEquals(10, rowCount());
        assertEquals(10L * FRAME_BYTES, result.bytesAfter());
    }

    @Test
    void anOverBudgetTableIsTrimmedToTheTarget() throws Exception {
        insertFrames(100, Instant.now());
        // Budget for 50 frames; the trim stops at 90% of it, so exactly 45 survive.
        long budget = 50L * FRAME_BYTES;

        SweepResult result = sweeper.sweep(policy(budget, null), Instant.now());

        assertTrue(result.overBudget());
        assertEquals(45, rowCount(), "trimmed to the target, not past it");
        assertEquals(45L * FRAME_BYTES, result.bytesAfter());
        assertEquals(55, result.rowsDroppedByBytes());
    }

    @Test
    void theOldestFramesAreTheOnesDropped() throws Exception {
        insertFrames(100, Instant.now());

        sweeper.sweep(policy(50L * FRAME_BYTES, null), Instant.now());

        // Ids are monotonic, so the survivors are the highest — the newest frames.
        assertEquals(100 - rowCount() + 1, lowestId());
    }

    @Test
    void repeatedSweepsOnASettledTableDropNothingFurther() throws Exception {
        insertFrames(100, Instant.now());
        RetentionPolicy policy = policy(50L * FRAME_BYTES, null);
        sweeper.sweep(policy, Instant.now());
        long settled = rowCount();

        SweepResult second = sweeper.sweep(policy, Instant.now());

        // The headroom below the budget is what stops a table sitting on its limit from
        // deleting on every single sweep.
        assertEquals(0, second.rowsDropped());
        assertEquals(settled, rowCount());
    }

    /**
     * The reason the budget counts {@code octet_length} rather than {@code pg_total_relation_size}:
     * a delete does not return space to the filesystem, so a loop that trimmed until the relation
     * size fell would never stop until the table was empty.
     */
    @Test
    void deletingRowsDoesNotShrinkTheRelationSize() throws Exception {
        insertFrames(100, Instant.now());
        long relationSizeBefore = relationSize();

        SweepResult result = sweeper.sweep(policy(50L * FRAME_BYTES, null), Instant.now());

        assertTrue(result.rowsDropped() > 0, "the sweep did drop rows");
        assertTrue(relationSize() >= relationSizeBefore,
                "relation size does not fall on delete; only VACUUM FULL returns space");
        assertTrue(result.bytesAfter() < result.bytesBefore(), "but retained payload bytes do");
    }

    private long relationSize() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT pg_total_relation_size('frames')")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    // --- age bound ----------------------------------------------------------------------------

    @Test
    void framesOlderThanTheAgeBoundAreDropped() throws Exception {
        Instant now = Instant.now();
        insertFrames(60, now);   // one per second, so id 1 is 59 seconds old

        SweepResult result = sweeper.sweep(policy(null, Duration.ofSeconds(30)), now);

        assertEquals(29, result.rowsDroppedByAge());
        assertEquals(31, rowCount());
    }

    @Test
    void theAgeBoundRunsBeforeTheByteBudget() throws Exception {
        Instant now = Instant.now();
        insertFrames(60, now);

        SweepResult result = sweeper.sweep(
                policy(100L * 1024 * 1024, Duration.ofSeconds(30)), now);

        // Age alone brought it under a budget it was never near, so the budget dropped nothing.
        assertEquals(29, result.rowsDroppedByAge());
        assertEquals(0, result.rowsDroppedByBytes());
    }

    @Test
    void whicheverBoundBitesFirstWins() throws Exception {
        Instant now = Instant.now();
        insertFrames(60, now);

        // Nothing is old enough to age out, so only the budget applies.
        SweepResult result = sweeper.sweep(
                policy(20L * FRAME_BYTES, Duration.ofHours(1)), now);

        assertEquals(0, result.rowsDroppedByAge());
        assertEquals(42, result.rowsDroppedByBytes());
        assertEquals(18, rowCount(), "90% of a 20-frame budget");
    }

    @Test
    void bothBoundsDisabledIsANoOp() throws Exception {
        insertFrames(20, Instant.now());

        SweepResult result = sweeper.sweep(policy(null, null), Instant.now());

        assertEquals(0, result.rowsDropped());
        assertEquals(20, rowCount());
    }

    @Test
    void anEmptyTableSweepsCleanly() throws Exception {
        SweepResult result = sweeper.sweep(policy(1L, Duration.ofSeconds(1)), Instant.now());

        assertEquals(0, result.rowsDropped());
        assertEquals(0, result.bytesAfter());
    }

    // --- batching -----------------------------------------------------------------------------

    @Test
    void aTrimLargerThanOneBatchStillCompletes() throws Exception {
        insertFrames(100, Instant.now());
        // Batch of 7 does not divide the work evenly, so this also covers the final short batch.
        RetentionPolicy small = new RetentionPolicy("frames", "id", "captured_at", "payload",
                10L * FRAME_BYTES, null, 7, RetentionPolicy.DEFAULT_TRIM_TO_FRACTION);

        SweepResult result = sweeper.sweep(small, Instant.now());

        // 9 frames is 90% of a 10-frame budget, reached seven rows at a time.
        assertEquals(9, rowCount(), "trimmed to budget across many batches");
        assertEquals(91, result.rowsDroppedByBytes());
    }

    @Test
    void anAgeSweepLargerThanOneBatchStillCompletes() throws Exception {
        Instant now = Instant.now();
        insertFrames(100, now);
        RetentionPolicy small = new RetentionPolicy("frames", "id", "captured_at", "payload",
                null, Duration.ofSeconds(10), 7, RetentionPolicy.DEFAULT_TRIM_TO_FRACTION);

        SweepResult result = sweeper.sweep(small, now);

        assertEquals(89, result.rowsDroppedByAge());
        assertEquals(11, rowCount());
    }

    // --- guardrails ---------------------------------------------------------------------------

    @Test
    void anIdentifierThatIsNotPlainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RetentionPolicy.of(
                "frames; DROP TABLE users", "id", "captured_at", "payload", 1L, null));
    }

    @Test
    void aNonPositiveBatchSizeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionPolicy("frames", "id", "captured_at", "payload", 1L, null, 0,
                        RetentionPolicy.DEFAULT_TRIM_TO_FRACTION));
    }

    @Test
    void aTrimFractionOutsideTheUnitRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetentionPolicy("frames", "id", "captured_at", "payload", 1L, null,
                        RetentionPolicy.DEFAULT_BATCH_SIZE, 1.5));
    }
}

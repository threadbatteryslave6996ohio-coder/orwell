package dev.orwell.bucket.retention;

import dev.orwell.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Trims an append-only table back inside its {@link RetentionPolicy}.
 *
 * <p>Age first, then bytes: dropping stale rows is usually enough, and doing it first means the
 * byte trim only has to deal with what a genuinely busy period produced.
 *
 * <p><strong>Deletes are batched, and each batch is its own transaction.</strong> Removing half a
 * large table in one statement is a long transaction, a single enormous WAL burst and a vacuum
 * storm afterwards — all while the writer that feeds the table is still queuing. Small committed
 * batches spread that out, and let a sweep that runs out of time leave the table in a sane state
 * rather than rolling everything back.
 *
 * <p><strong>This class owns its transactions through the JDBC connection.</strong> That is
 * deliberate and is the reason it takes a {@code DataSource} rather than being a Spring bean with
 * {@code @Transactional}: a self-invoked or non-public {@code @Transactional} method is silently
 * not advised by a proxy, which turns a bulk delete into an exception caught by whatever wrapper
 * logs it — a retention job that looks like it is running and is not. Owning the connection makes
 * that failure mode unreachable, and lets the sweeper run under a non-Spring engine too.
 */
public class RetentionSweeper {
    private final DataSource dataSource;
    private final Logger logger;

    public RetentionSweeper(DataSource dataSource, Logger logger) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Runs one pass. Throws {@link SQLException} rather than swallowing it — a caller on a
     * scheduler decides whether a failed sweep is worth a warning or an alert, but it must be
     * able to tell that it failed.
     */
    public SweepResult sweep(RetentionPolicy policy, Instant now) throws SQLException {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(now, "now");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);

            long bytesBefore = retainedBytes(connection, policy);
            long droppedByAge = policy.boundsAge()
                    ? dropOlderThan(connection, policy, now.minus(policy.maxAge()))
                    : 0;

            if (!policy.boundsBytes()) {
                long bytesAfter = droppedByAge == 0 ? bytesBefore : retainedBytes(connection, policy);
                return new SweepResult(droppedByAge, 0, bytesBefore, bytesAfter, false);
            }

            long bytes = droppedByAge == 0 ? bytesBefore : retainedBytes(connection, policy);
            boolean overBudget = bytes > policy.maxBytes();
            long droppedByBytes = overBudget ? trimToBudget(connection, policy, bytes) : 0;
            long bytesAfter = droppedByBytes == 0 ? bytes : retainedBytes(connection, policy);
            return new SweepResult(droppedByAge, droppedByBytes, bytesBefore, bytesAfter, overBudget);
        }
    }

    /**
     * Payload bytes currently retained.
     *
     * <p>{@code octet_length} on a large value is answered from the TOAST pointer's stored raw
     * size, so this reads the heap without fetching a single frame back out of TOAST — which is
     * what makes it cheap enough to run on every sweep.
     */
    private long retainedBytes(Connection connection, RetentionPolicy policy) throws SQLException {
        String sql = "SELECT COALESCE(SUM(octet_length(" + policy.payloadColumn() + ")), 0) FROM "
                + policy.table();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /** Drops rows older than {@code cutoff}, in batches, until none are left. */
    private long dropOlderThan(Connection connection, RetentionPolicy policy, Instant cutoff)
            throws SQLException {
        // The subselect is what bounds the batch: a bare DELETE ... WHERE timestamp < ? would
        // take the whole backlog in one transaction, which is the thing being avoided.
        String sql = "DELETE FROM " + policy.table() + " WHERE " + policy.idColumn() + " IN ("
                + "SELECT " + policy.idColumn() + " FROM " + policy.table()
                + " WHERE " + policy.timestampColumn() + " < ?"
                + " ORDER BY " + policy.idColumn() + " LIMIT " + policy.batchSize() + ")";
        long dropped = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            while (true) {
                statement.setTimestamp(1, Timestamp.from(cutoff));
                int deleted = statement.executeUpdate();
                dropped += deleted;
                if (deleted < policy.batchSize()) {
                    return dropped;
                }
            }
        }
    }

    /**
     * Drops the oldest rows until the retained payload is back under the trim target.
     *
     * <p>A batch deletes only what is still over the target, never a fixed count: the running
     * total of {@code octet_length} over the oldest rows says exactly how far to cut, and the
     * batch stops at the row that crosses the excess. Deleting a flat {@code batchSize} instead
     * would overshoot by most of a batch whenever the overshoot is small — with a 500-row batch
     * and 20 rows over budget, it would take 500 and throw away replayable history nobody asked
     * it to. The batch size remains the ceiling, so a big trim is still many small transactions.
     *
     * <p>Each batch reports the bytes it removed, so the running total stays exact without
     * re-summing the table per batch. The loop also stops if a batch deletes nothing, which keeps
     * an over-budget-but-unshrinkable table from spinning.
     */
    private long trimToBudget(Connection connection, RetentionPolicy policy, long startingBytes)
            throws SQLException {
        String payload = policy.payloadColumn();
        String id = policy.idColumn();
        String sql = "DELETE FROM " + policy.table() + " WHERE " + id + " IN ("
                + "SELECT " + id + " FROM ("
                + "SELECT " + id + ", octet_length(" + payload + ") AS len,"
                + " SUM(octet_length(" + payload + ")) OVER (ORDER BY " + id + ") AS running"
                + " FROM " + policy.table()
                + " ORDER BY " + id + " LIMIT " + policy.batchSize() + ") ranked"
                // Keeps every row whose predecessors alone did not cover the excess — that is,
                // up to and including the row that crosses it, and not one row further.
                + " WHERE running - len < ?)"
                + " RETURNING octet_length(" + payload + ")";
        long target = policy.trimTarget();
        long bytes = startingBytes;
        long dropped = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            while (bytes > target) {
                statement.setLong(1, bytes - target);
                long batchRows = 0;
                long batchBytes = 0;
                try (ResultSet deleted = statement.executeQuery()) {
                    while (deleted.next()) {
                        batchRows++;
                        batchBytes += deleted.getLong(1);
                    }
                }
                if (batchRows == 0) {
                    break;
                }
                bytes -= batchBytes;
                dropped += batchRows;
            }
        }
        if (dropped > 0) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("table", policy.table());
            metadata.put("rowsDropped", dropped);
            metadata.put("maxBytes", policy.maxBytes());
            metadata.put("bytesAfter", bytes);
            logger.info("Trimmed a table back under its byte budget.", metadata);
        }
        return dropped;
    }
}

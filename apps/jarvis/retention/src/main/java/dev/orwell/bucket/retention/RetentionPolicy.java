package dev.orwell.bucket.retention;

import java.time.Duration;
import java.util.Objects;

/**
 * What to keep in an append-only table, and what to drop.
 *
 * <p>Two independent bounds, either of which may be disabled, and whichever bites first wins:
 *
 * <ul>
 *   <li><b>{@code maxBytes}</b> — a budget on the payload currently stored. This is the bound that
 *       matters when a table holds large values, because it is the one that tracks the cost.
 *   <li><b>{@code maxAge}</b> — an upper bound on how stale a retained row may be. On a quiet
 *       table the byte budget alone would keep rows forever; this is what stops that.
 * </ul>
 *
 * <p>The bytes counted are {@code SUM(octet_length(payloadColumn))} — the payload actually
 * retained, not the on-disk footprint of the table. Those differ: indexes, per-row overhead and
 * not-yet-vacuumed dead rows all cost disk beyond this number, so a deployment should set the
 * budget comfortably below the disk it is willing to spend. It is measured this way on purpose —
 * {@code pg_total_relation_size} does not shrink when rows are deleted (only {@code VACUUM FULL}
 * returns space to the filesystem), so trimming until <em>that</em> number drops would never
 * terminate and would empty the table.
 *
 * @param table          the table to sweep. Interpolated into SQL, so it must be a trusted
 *                       identifier from configuration, never anything a request can influence.
 * @param idColumn       monotonic id; "oldest" means lowest, and it is what batches delete by.
 * @param timestampColumn column the age bound compares against.
 * @param payloadColumn  the large column whose retained bytes the budget counts.
 * @param maxBytes       payload budget in bytes; null or non-positive disables the bound.
 * @param maxAge         maximum age of a retained row; null or zero disables the bound.
 * @param batchSize      rows deleted per transaction. Small keeps each delete short and the WAL
 *                       burst modest, at the cost of more round trips.
 * @param trimToFraction how far below {@code maxBytes} a byte-budget trim goes before stopping,
 *                       so a table sitting exactly on its budget does not delete on every sweep.
 */
public record RetentionPolicy(
        String table,
        String idColumn,
        String timestampColumn,
        String payloadColumn,
        Long maxBytes,
        Duration maxAge,
        int batchSize,
        double trimToFraction) {

    /** Rows per delete transaction when the caller does not care to choose. */
    public static final int DEFAULT_BATCH_SIZE = 500;
    /** Trim to 90% of budget, leaving headroom so the next sweep is usually a no-op. */
    public static final double DEFAULT_TRIM_TO_FRACTION = 0.9;

    public RetentionPolicy {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(idColumn, "idColumn");
        Objects.requireNonNull(timestampColumn, "timestampColumn");
        Objects.requireNonNull(payloadColumn, "payloadColumn");
        requireIdentifier(table);
        requireIdentifier(idColumn);
        requireIdentifier(timestampColumn);
        requireIdentifier(payloadColumn);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got " + batchSize);
        }
        if (trimToFraction <= 0 || trimToFraction > 1) {
            throw new IllegalArgumentException(
                    "trimToFraction must be in (0, 1], got " + trimToFraction);
        }
    }

    /** The common case: both bounds, default batching. */
    public static RetentionPolicy of(String table, String idColumn, String timestampColumn,
            String payloadColumn, Long maxBytes, Duration maxAge) {
        return new RetentionPolicy(table, idColumn, timestampColumn, payloadColumn, maxBytes,
                maxAge, DEFAULT_BATCH_SIZE, DEFAULT_TRIM_TO_FRACTION);
    }

    public boolean boundsBytes() {
        return maxBytes != null && maxBytes > 0;
    }

    public boolean boundsAge() {
        return maxAge != null && !maxAge.isZero() && !maxAge.isNegative();
    }

    /** Where a byte-budget trim stops, leaving headroom below the budget. */
    long trimTarget() {
        return (long) (maxBytes * trimToFraction);
    }

    /**
     * Table and column names are interpolated into SQL rather than bound as parameters, because
     * an identifier cannot be a bind parameter. They come from configuration, but "it comes from
     * config" has been the opening line of enough incidents that this checks anyway.
     */
    private static void requireIdentifier(String value) {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("not a plain SQL identifier: " + value);
        }
    }
}

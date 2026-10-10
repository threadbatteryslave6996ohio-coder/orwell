package dev.orwell.bucket.viewer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything this app asks {@code frame_events}. All of it {@code SELECT}.
 *
 * <p><strong>Why read the table instead of calling the hub.</strong> The hub's {@code GET /frames}
 * pages <em>forward</em> from a time window, which is the wrong shape for a viewer that opens on the
 * newest frame and walks backwards: there is no route for "the latest frame from this camera", no
 * route for "the one before this", and no route for the device list at all — a source only exists as
 * a value in this column. Answering those over HTTP would mean fetching a page of base64 to display
 * one frame from it. Reading the table directly is one indexed seek per navigation step, and it
 * keeps a browser scrubbing through history off the hub's heap, which is the process that must stay
 * up for the recording to continue. {@code jarvis-retention-worker} sets the precedent for a second
 * process on this table; this one is the read-only case of it.
 *
 * <p><strong>Every navigation step is one row.</strong> {@code idx_frame_events_source_id} is
 * {@code (source, id)}, so {@code WHERE source = ? AND id < ? ORDER BY id DESC LIMIT 1} is a single
 * index seek regardless of how much history is retained — stepping back through an hour of frames
 * costs the same per step as the first one.
 *
 * <p><strong>The bytes are fetched by their own query and nothing else selects them.</strong>
 * {@code octet_length(frame_bytes)} is answered from the TOAST pointer's stored size, so the
 * metadata queries here read the heap without pulling a single frame back out of TOAST — the same
 * property {@code RetentionSweeper} relies on to sum the table cheaply. That is what makes a
 * 200-row list cost kilobytes rather than megabytes.
 */
final class FrameQueries {
    /**
     * The metadata projection, used by every query that is not fetching an image.
     *
     * <p>Note what is missing: {@code frame_bytes} itself. Selecting it here would be the single
     * easiest way to turn this app into a memory problem — a page of 200 frames would be ~8 MB of
     * heap for a list nobody is looking at the pictures in.
     */
    private static final String COLUMNS =
            "id, source, frame_index, sha256, captured_at, octet_length(frame_bytes) AS bytes";

    private FrameQueries() {
    }

    /**
     * Every device with frames still stored, newest activity first.
     *
     * <p>Grouped straight off {@code (source, id)}, and deliberately without a
     * {@code sum(octet_length(frame_bytes))}: a total-bytes-per-source column looks free next to the
     * counts and is not — it is the one aggregate here that has to visit every row's payload size
     * across the whole table, on every page load.
     */
    static List<FrameSource> sources(Connection connection) throws SQLException {
        String sql = "SELECT source, count(*) AS frames, min(id) AS oldest_id, max(id) AS newest_id,"
                + " min(captured_at) AS oldest_at, max(captured_at) AS newest_at"
                + " FROM frame_events GROUP BY source ORDER BY max(id) DESC";
        List<FrameSource> sources = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                sources.add(new FrameSource(
                        rows.getString("source"),
                        rows.getLong("frames"),
                        rows.getLong("oldest_id"),
                        rows.getLong("newest_id"),
                        instant(rows, "oldest_at"),
                        instant(rows, "newest_at")));
            }
        }
        return sources;
    }

    /** The newest frame stored, for one source or across all of them. Where the viewer opens. */
    static Optional<StoredFrame> latest(Connection connection, String source) throws SQLException {
        return source == null
                ? first(connection, null, "DESC")
                : first(connection, "source = ?", "DESC", source);
    }

    /** The oldest frame still stored — where stepping back runs out of history. */
    static Optional<StoredFrame> oldest(Connection connection, String source) throws SQLException {
        return source == null
                ? first(connection, null, "ASC")
                : first(connection, "source = ?", "ASC", source);
    }

    /** One frame by id, whatever source it came from. */
    static Optional<StoredFrame> byId(Connection connection, long frameId) throws SQLException {
        return first(connection, "id = ?", "ASC", frameId);
    }

    /** The frame immediately before this one — the "back" step. */
    static Optional<StoredFrame> older(Connection connection, String source, long frameId)
            throws SQLException {
        return source == null
                ? first(connection, "id < ?", "DESC", frameId)
                : first(connection, "source = ? AND id < ?", "DESC", source, frameId);
    }

    /** The frame immediately after this one. */
    static Optional<StoredFrame> newer(Connection connection, String source, long frameId)
            throws SQLException {
        return source == null
                ? first(connection, "id > ?", "ASC", frameId)
                : first(connection, "source = ? AND id > ?", "ASC", source, frameId);
    }

    /**
     * How many of this source's stored frames are this one or older — so the page can say "1240 of
     * 1240" and mean it.
     *
     * <p>Counted rather than derived from {@code id - oldestId}: ids come from a shared sequence
     * across every source and retention deletes from the middle of the table, so the gap between
     * two ids is not the number of frames between them.
     */
    static long positionOf(Connection connection, String source, long frameId) throws SQLException {
        return source == null
                ? count(connection, "id <= ?", frameId)
                : count(connection, "source = ? AND id <= ?", source, frameId);
    }

    /** How many frames this source has stored in total. */
    static long total(Connection connection, String source) throws SQLException {
        return source == null ? count(connection, null) : count(connection, "source = ?", source);
    }

    /**
     * A page of frame metadata, newest first, for the list a viewer scrubs with.
     *
     * <p>Paged by id descending from an exclusive {@code before} cursor, which is the same direction
     * the buttons move in — so "next page" is "keep going back", not a separate coordinate system.
     * Carries no image bytes; the list is text, and only the frame actually on screen is fetched.
     */
    static List<StoredFrame> page(Connection connection, String source, Long before, int limit)
            throws SQLException {
        String where = before == null
                ? (source == null ? null : "source = ?")
                : (source == null ? "id < ?" : "source = ? AND id < ?");
        List<Object> parameters = new ArrayList<>();
        if (source != null) {
            parameters.add(source);
        }
        if (before != null) {
            parameters.add(before);
        }
        String sql = "SELECT " + COLUMNS + " FROM frame_events"
                + (where == null ? "" : " WHERE " + where)
                + " ORDER BY id DESC LIMIT " + limit;
        List<StoredFrame> frames = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters.toArray());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    frames.add(read(rows));
                }
            }
        }
        return frames;
    }

    /**
     * The frame itself.
     *
     * <p>The only query that touches {@code frame_bytes}, and it fetches exactly one row — so the
     * app's memory ceiling is one frame per in-flight image request rather than a page of them.
     */
    static Optional<byte[]> imageBytes(Connection connection, long frameId) throws SQLException {
        String sql = "SELECT frame_bytes FROM frame_events WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, frameId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getBytes(1)) : Optional.empty();
            }
        }
    }

    /** How many frames are stored across every source, for {@code /health}. */
    static long storedFrames(Connection connection) throws SQLException {
        return count(connection, null);
    }

    /**
     * The first row of {@code WHERE <where> ORDER BY id <direction> LIMIT 1}.
     *
     * <p>Two predicate shapes are passed in from the callers above rather than one
     * {@code (? IS NULL OR source = ?)} covering both. That form reads as less duplication and
     * costs the index: Postgres cannot use {@code (source, id)} for a condition it can only
     * evaluate row by row, so an unscoped viewer would degrade from a seek to a scan of the whole
     * retained window. The hub's repository splits its queries in two for a related reason, stated
     * on {@code FrameEventRepository.findCapturedBetween}.
     */
    private static Optional<StoredFrame> first(Connection connection, String where,
            String direction, Object... parameters) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM frame_events"
                + (where == null ? "" : " WHERE " + where)
                + " ORDER BY id " + direction + " LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    private static long count(Connection connection, String where, Object... parameters)
            throws SQLException {
        String sql = "SELECT count(*) FROM frame_events" + (where == null ? "" : " WHERE " + where);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        }
    }

    /** Positional binding for the handful of parameter types these queries use. */
    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object parameter = parameters[index];
            if (parameter instanceof Long value) {
                statement.setLong(index + 1, value);
            } else {
                statement.setString(index + 1, String.valueOf(parameter));
            }
        }
    }

    private static StoredFrame read(ResultSet rows) throws SQLException {
        // frame_index is nullable, and getLong answers 0 for NULL — so the frame counter of a
        // producer that sends none would read as frame 0 rather than as absent. wasNull() reports on
        // the most recent read, so it has to be asked here and not inside the constructor call.
        long frameIndex = rows.getLong("frame_index");
        Long producerIndex = rows.wasNull() ? null : frameIndex;
        return new StoredFrame(
                rows.getLong("id"),
                rows.getString("source"),
                producerIndex,
                rows.getString("sha256"),
                instant(rows, "captured_at"),
                rows.getLong("bytes"));
    }

    /**
     * {@code captured_at} is {@code timestamp with time zone}, which is what Hibernate creates for
     * the hub's {@code Instant} field. Read as an {@code OffsetDateTime} rather than through
     * {@code getTimestamp}, so the value does not pass through the JVM's default zone on the way
     * out — this process and the hub need not agree about {@code TZ} to agree about when a frame
     * was captured.
     */
    private static Instant instant(ResultSet rows, String column) throws SQLException {
        OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

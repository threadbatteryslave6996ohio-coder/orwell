package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.InstagramPost;
import dev.orwell.logging.Logger;
import dev.orwell.primitives.Sha256;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Records the posts a profile lookup returned.
 *
 * <p>A post splits three ways because its parts change at different rates. Identity and publication
 * facts never change, so they are one row. The caption can be edited, so it is a value history like
 * a bio. Likes and comments move on almost every observation, so they are a time series — and the
 * only real one in this schema, which is why {@code post_metric} exists where the equivalent table
 * for accounts was dropped.
 *
 * <p><b>Nothing here ever sets {@code deleted_at}.</b> These posts come from {@code latestPosts},
 * which is the twelve most recent — a truncated listing by definition. Applying the absence rule
 * that works for follows would mark every post older than the newest twelve as deleted, every run.
 * Deletion detection has to wait for a complete listing from the dedicated post actor.
 */
public final class PostWriter {
    private final Connection connection;
    private final PictureSource media;
    private final PictureStore store;
    private final Logger logger;

    public PostWriter(
            Connection connection, PictureSource media, PictureStore store, Logger logger) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.media = Objects.requireNonNull(media, "media");
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return how many of {@code posts} were recorded. */
    public int record(String accountId, List<InstagramPost> posts, Instant observedAt)
            throws SQLException {
        int recorded = 0;
        for (InstagramPost post : posts) {
            if (post.id() == null || post.id().isBlank()) {
                continue;
            }
            upsertPost(accountId, post, observedAt);
            if (post.caption() != null) {
                upsertCaption(post.id(), post.caption(), observedAt);
            }
            upsertMetric(post, observedAt);
            recordMedia(post, observedAt);
            recorded++;
        }
        if (recorded > 0) {
            logger.info("Recorded posts.", Map.of("accountId", accountId, "posts", recorded));
        }
        return recorded;
    }

    private void upsertPost(String accountId, InstagramPost post, Instant observedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO post
                    (id, account_id, short_code, taken_at, type, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                SET last_seen_at = EXCLUDED.last_seen_at,
                    -- A post seen again is a post that exists; if it had been marked deleted by a
                    -- complete listing, seeing it undoes that, exactly as a re-follow does.
                    deleted_at = NULL""")) {
            statement.setString(1, post.id());
            statement.setString(2, accountId);
            statement.setString(3, post.shortCode());
            statement.setTimestamp(4, post.takenAt() == null ? null : Timestamp.from(post.takenAt()));
            statement.setString(5, post.type());
            statement.setTimestamp(6, Timestamp.from(observedAt));
            statement.setTimestamp(7, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    private void upsertCaption(String postId, String caption, Instant observedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO post_caption
                    (post_id, caption_hash, caption, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (post_id, caption_hash)
                DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at""")) {
            statement.setString(1, postId);
            statement.setString(2, Sha256.hex(caption.getBytes(StandardCharsets.UTF_8)));
            statement.setString(3, caption);
            statement.setTimestamp(4, Timestamp.from(observedAt));
            statement.setTimestamp(5, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    /**
     * Appends to the series only when a number actually moved. A daily sync of a post nobody
     * touched should cost one {@code UPDATE} of a timestamp, not a duplicate row per day forever —
     * and {@code last_seen_at} still records that the flat value was observed all along.
     */
    private void upsertMetric(InstagramPost post, Instant observedAt) throws SQLException {
        Optional<Instant> unchanged = latestMatching(post);
        if (unchanged.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE post_metric SET last_seen_at = ? WHERE post_id = ? AND first_seen_at = ?")) {
                statement.setTimestamp(1, Timestamp.from(observedAt));
                statement.setString(2, post.id());
                statement.setTimestamp(3, Timestamp.from(unchanged.get()));
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO post_metric
                    (post_id, likes_count, comments_count, video_view_count,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (post_id, first_seen_at)
                DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at""")) {
            statement.setString(1, post.id());
            setNullableLong(statement, 2, post.likesCount());
            setNullableLong(statement, 3, post.commentsCount());
            setNullableLong(statement, 4, post.videoViewCount());
            statement.setTimestamp(5, Timestamp.from(observedAt));
            statement.setTimestamp(6, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    /** @return the newest series row's {@code first_seen_at} if its numbers match this reading. */
    private Optional<Instant> latestMatching(InstagramPost post) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT first_seen_at, likes_count, comments_count, video_view_count
                FROM post_metric WHERE post_id = ? ORDER BY last_seen_at DESC LIMIT 1""")) {
            statement.setString(1, post.id());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                boolean same = sameNumber(results, "likes_count", post.likesCount())
                        && sameNumber(results, "comments_count", post.commentsCount())
                        && sameNumber(results, "video_view_count", post.videoViewCount());
                return same
                        ? Optional.of(results.getTimestamp("first_seen_at").toInstant())
                        : Optional.empty();
            }
        }
    }

    private void recordMedia(InstagramPost post, Instant observedAt) {
        if (post.displayUrl() == null || post.displayUrl().isBlank() || !store.enabled()) {
            return;
        }
        try {
            byte[] bytes = media.fetch(post.displayUrl());
            String hash = Sha256.hex(bytes);
            String key = existingKey(hash).orElse(null);
            if (key == null) {
                key = store.put(hash, bytes);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO post_media
                        (post_id, content_hash, bucket_key, source_url, position, byte_size,
                         first_seen_at, last_seen_at)
                    VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                    ON CONFLICT (post_id, content_hash)
                    DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at,
                                  source_url = EXCLUDED.source_url""")) {
                statement.setString(1, post.id());
                statement.setString(2, hash);
                statement.setString(3, key);
                statement.setString(4, post.displayUrl());
                statement.setInt(5, bytes.length);
                statement.setTimestamp(6, Timestamp.from(observedAt));
                statement.setTimestamp(7, Timestamp.from(observedAt));
                statement.executeUpdate();
            }
        } catch (Exception exception) {
            // Losing an image must not lose the post it belongs to.
            logger.warn("Could not store post media.", Map.of(
                    "postId", post.id(), "error", exception.toString()));
        }
    }

    private Optional<String> existingKey(String contentHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT bucket_key FROM post_media WHERE content_hash = ? LIMIT 1")) {
            statement.setString(1, contentHash);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    private static boolean sameNumber(ResultSet results, String column, Long value)
            throws SQLException {
        long stored = results.getLong(column);
        if (results.wasNull()) {
            return value == null;
        }
        return value != null && value == stored;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}

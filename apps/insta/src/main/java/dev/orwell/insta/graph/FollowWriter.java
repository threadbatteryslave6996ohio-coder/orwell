package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one walk of a follower or following list into edges, and — only when the walk can be
 * trusted — into unfollows.
 *
 * <p>Recording follows is the easy half: insert what is new, refresh what is not. The hard half is
 * that <b>an unfollow is never observed</b>. Instagram does not report one; you infer it from an
 * account being absent from a walk that saw <em>everything</em>. So retirement is driven by the
 * {@code last_seen_at} watermark — anything a walk did not refresh is gone — and that inference is
 * only valid when the walk was complete.
 *
 * <p>Three things make a walk untrustworthy, and each of them, left unchecked, invents unfollows
 * that never happened:
 *
 * <ul>
 *   <li><b>It stopped early.</b> A page cap, a timeout, an expired cursor or an exhausted Apify
 *       balance all end a walk with accounts unseen. 500 of 40,000 followers would retire 39,500.
 *   <li><b>Rows had no id.</b> A row that cannot be keyed cannot be stored, and an unstorable row
 *       looks exactly like an absent one. Any of them downgrades the walk.
 *   <li><b>It retired implausibly much.</b> A private or deleted account returns an empty list,
 *       which is a perfect impression of everybody leaving at once. Above a threshold this refuses
 *       to act and says so, because the alternative is thousands of wrong alerts.
 * </ul>
 */
public final class FollowWriter {
    /** Above this share of an account's live edges, a single walk is not allowed to retire. */
    public static final double DEFAULT_MAX_RETIRE_FRACTION = 0.2;
    /** Below this many live edges the fraction is meaningless, so small accounts are exempt. */
    private static final int FRACTION_APPLIES_ABOVE = 25;

    private final Connection connection;
    private final AccountWriter accounts;
    private final double maxRetireFraction;
    private final Logger logger;

    public FollowWriter(
            Connection connection, AccountWriter accounts, double maxRetireFraction, Logger logger) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.maxRetireFraction = maxRetireFraction;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * @param subjectId   the account that was walked
     * @param direction   {@code FOLLOWERS} means every result follows the subject;
     *                    {@code FOLLOWING} means the subject follows every result
     * @param walkStarted the watermark — edges not refreshed since this are candidates for
     *                    retirement, so it must be read <em>before</em> the walk began
     * @param complete    whether the walk reached the end of the list rather than stopping
     */
    public FollowDiff record(
            String subjectId,
            ConnectionType direction,
            List<InstagramAccount> seen,
            Instant walkStarted,
            Instant observedAt,
            boolean complete) throws SQLException {

        accounts.upsertAccount(subjectId, observedAt);

        int skippedNoId = 0;
        int added = 0;
        for (InstagramAccount account : seen) {
            if (account.id() == null || account.id().isBlank()) {
                skippedNoId++;
                continue;
            }
            accounts.record(account, observedAt);
            added += recordFollow(
                    followeeOf(subjectId, account, direction),
                    followerOf(subjectId, account, direction),
                    observedAt);
        }

        String skipReason = retirementBlockedBecause(complete, skippedNoId);
        int retired = 0;
        if (skipReason == null) {
            int live = liveEdges(subjectId, direction);
            int candidates = staleEdges(subjectId, direction, walkStarted);
            if (implausible(live, candidates)) {
                skipReason = "would retire " + candidates + " of " + live + " edges";
                logger.error("Refused to retire edges: the walk lost implausibly many at once.",
                        Map.of("accountId", subjectId, "candidates", candidates, "live", live));
            } else {
                retired = retire(subjectId, direction, walkStarted, observedAt);
            }
        }

        FollowDiff diff = new FollowDiff(
                seen.size() - skippedNoId, skippedNoId, added, retired, skipReason);
        logger.info("Recorded a follow walk.", Map.of(
                "accountId", subjectId,
                "direction", direction.name(),
                "seen", diff.seen(),
                "added", diff.added(),
                "retired", diff.retired(),
                "retirementRan", diff.retirementRan()));
        return diff;
    }

    /**
     * Refreshes the edge and, when this is the start of a follow rather than the continuation of
     * one, appends a {@code follows} row.
     *
     * <p>The {@code prior} CTE reads the row as it was before the upsert, which is the only way to
     * tell a return from a continuation: {@code RETURNING} sees the new row, and {@code xmax}
     * distinguishes insert from update but not reactivation from refresh.
     *
     * @return 1 if a follow began here, 0 if it was already running.
     */
    private int recordFollow(String followeeId, String followerId, Instant observedAt)
            throws SQLException {
        long edgeId;
        boolean began;
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH prior AS (
                    SELECT id, active FROM follow_edge
                    WHERE followee_id = ? AND follower_id = ?
                ), upserted AS (
                    INSERT INTO follow_edge
                        (followee_id, follower_id, first_seen_at, last_seen_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT ON CONSTRAINT follow_edge_pair_key DO UPDATE
                    SET last_seen_at = EXCLUDED.last_seen_at, active = TRUE
                    RETURNING id, (xmax = 0) AS inserted
                )
                SELECT u.id, u.inserted, COALESCE(p.active, TRUE) AS was_active
                FROM upserted u LEFT JOIN prior p ON p.id = u.id""")) {
            statement.setString(1, followeeId);
            statement.setString(2, followerId);
            statement.setString(3, followeeId);
            statement.setString(4, followerId);
            statement.setTimestamp(5, Timestamp.from(observedAt));
            statement.setTimestamp(6, Timestamp.from(observedAt));
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return 0;
                }
                edgeId = results.getLong("id");
                began = results.getBoolean("inserted") || !results.getBoolean("was_active");
            }
        }
        if (!began) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO follows (edge_id, at) VALUES (?, ?)")) {
            statement.setLong(1, edgeId);
            statement.setTimestamp(2, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
        return 1;
    }

    private String retirementBlockedBecause(boolean complete, int skippedNoId) {
        if (!complete) {
            return "the walk did not reach the end of the list";
        }
        if (skippedNoId > 0) {
            return skippedNoId + " rows had no id, so the walk did not see everything it returned";
        }
        return null;
    }

    private boolean implausible(int live, int candidates) {
        return live > FRACTION_APPLIES_ABOVE && candidates > live * maxRetireFraction;
    }

    /**
     * Deactivates every edge the walk did not refresh and writes an {@code unfollows} row for each,
     * in one statement so the state and its history cannot disagree.
     */
    private int retire(
            String subjectId, ConnectionType direction, Instant walkStarted, Instant observedAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH retired AS (
                    UPDATE follow_edge SET active = FALSE
                    WHERE %s = ? AND active AND last_seen_at < ?
                    RETURNING id
                )
                INSERT INTO unfollows (edge_id, at) SELECT id, ? FROM retired
                """.formatted(subjectColumn(direction)))) {
            statement.setString(1, subjectId);
            statement.setTimestamp(2, Timestamp.from(walkStarted));
            statement.setTimestamp(3, Timestamp.from(observedAt));
            return statement.executeUpdate();
        }
    }

    private int liveEdges(String subjectId, ConnectionType direction) throws SQLException {
        return count("SELECT count(*) FROM follow_edge WHERE " + subjectColumn(direction)
                + " = ? AND active", subjectId, null);
    }

    private int staleEdges(String subjectId, ConnectionType direction, Instant walkStarted)
            throws SQLException {
        return count("SELECT count(*) FROM follow_edge WHERE " + subjectColumn(direction)
                + " = ? AND active AND last_seen_at < ?", subjectId, walkStarted);
    }

    private int count(String sql, String subjectId, Instant before) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, subjectId);
            if (before != null) {
                statement.setTimestamp(2, Timestamp.from(before));
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    /** Which end of the edge the walked account sits on. */
    private static String subjectColumn(ConnectionType direction) {
        return direction == ConnectionType.FOLLOWERS ? "followee_id" : "follower_id";
    }

    private static String followeeOf(
            String subjectId, InstagramAccount account, ConnectionType direction) {
        return direction == ConnectionType.FOLLOWERS ? subjectId : account.id();
    }

    private static String followerOf(
            String subjectId, InstagramAccount account, ConnectionType direction) {
        return direction == ConnectionType.FOLLOWERS ? account.id() : subjectId;
    }
}

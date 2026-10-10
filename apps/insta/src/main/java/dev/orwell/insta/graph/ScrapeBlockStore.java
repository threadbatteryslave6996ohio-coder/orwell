package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.ConnectionType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The list of accounts a walk must not touch, and the questions {@code sync} asks it.
 *
 * <p>A block is a <em>decision</em>, and the point of storing it is that a decision made once
 * should not be re-made — or re-paid for — on every run. Before this existed the only way to leave
 * an account alone was to leave it out of whatever drove the syncs, which is a rule living in a
 * cron file rather than beside the data it is about.
 *
 * <p>It is per direction, because the two lists are separately worth having: a shop's follower list
 * is thousands of strangers and its following list is a dozen accounts that say something about it.
 * "Both" is two rows rather than a third enum value, so a query never has to expand one.
 *
 * <p><b>A block stops the walk, not the profile lookup.</b> See {@code schema.sql} for why — in
 * short, the profile is the cheap half and it is the only thing that can notice a blocked account
 * has changed.
 */
public final class ScrapeBlockStore {
    private final Connection connection;

    public ScrapeBlockStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** The block covering this direction, if there is one. Empty means walk it. */
    public Optional<Block> find(String accountId, ConnectionType direction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT reason, blocked_at FROM scrape_block
                WHERE account_id = ? AND direction = ?""")) {
            statement.setString(1, accountId);
            statement.setString(2, direction.name());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Block(
                        accountId, null, direction, results.getString(1),
                        results.getTimestamp(2).toInstant()));
            }
        }
    }

    /** @return false if the block was already there, so the caller can say so. */
    public boolean block(String accountId, ConnectionType direction, String reason, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scrape_block (account_id, direction, reason, blocked_at)
                VALUES (?, ?, ?, ?)
                -- COALESCE, not EXCLUDED.reason: `insta block x --both` after `insta block x --followers
                -- --reason "..."` passes a null reason, and assigning it would erase the note with
                -- nothing said (the RETURNING below just reports "already blocked"). This table
                -- keeps no history, so an erased reason is unrecoverable. Clearing one is `unblock`.
                ON CONFLICT (account_id, direction) DO UPDATE
                    SET reason = COALESCE(EXCLUDED.reason, scrape_block.reason)
                RETURNING (xmax = 0) AS inserted""")) {
            statement.setString(1, accountId);
            statement.setString(2, direction.name());
            statement.setString(3, reason);
            statement.setTimestamp(4, Timestamp.from(at));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    /**
     * Lifts a block. Re-blocking later starts a fresh {@code blocked_at} rather than resurrecting
     * the old one — there is no history here to keep straight.
     *
     * @return false if there was nothing to lift.
     */
    public boolean unblock(String accountId, ConnectionType direction) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM scrape_block WHERE account_id = ? AND direction = ?")) {
            statement.setString(1, accountId);
            statement.setString(2, direction.name());
            return statement.executeUpdate() > 0;
        }
    }

    /** Every block, newest handle attached, for {@code insta blocks}. */
    public List<Block> all() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT b.account_id, b.direction, b.reason, b.blocked_at,
                       (SELECT u.username FROM account_username u
                         WHERE u.account_id = b.account_id
                         ORDER BY u.last_seen_at DESC LIMIT 1) AS username
                FROM scrape_block b
                ORDER BY username NULLS LAST, b.direction""");
             ResultSet results = statement.executeQuery()) {
            List<Block> blocks = new ArrayList<>();
            while (results.next()) {
                blocks.add(new Block(
                        results.getString(1),
                        results.getString(5),
                        ConnectionType.valueOf(results.getString(2)),
                        results.getString(3),
                        results.getTimestamp(4).toInstant()));
            }
            return blocks;
        }
    }

    /**
     * The account id behind a handle, from what has already been recorded — no Apify call, so a
     * block can be placed without spending anything.
     *
     * <p>Most recently seen wins. A released handle can be taken by someone else and
     * {@code account_username} keeps both rows, so this is a genuine ambiguity rather than a
     * lookup that cannot fail; the newest sighting is the one a person typing that handle means.
     */
    public Optional<String> accountIdFor(String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id FROM account_username
                WHERE lower(username) = lower(?)
                ORDER BY last_seen_at DESC LIMIT 1""")) {
            statement.setString(1, username);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    /** {@code username} is null when no handle has ever been recorded for the account. */
    public record Block(
            String accountId, String username, ConnectionType direction, String reason,
            Instant blockedAt) {

        /** What to call it in a message: the handle if we have one, the raw id if we do not. */
        public String label() {
            return username == null || username.isBlank() ? accountId : username;
        }
    }
}

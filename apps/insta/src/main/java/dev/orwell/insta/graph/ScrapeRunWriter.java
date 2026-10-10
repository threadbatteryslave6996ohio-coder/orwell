package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Records what one run saw, as it saw it.
 *
 * <p>{@link FollowWriter} writes the graph — current membership, plus the follows and unfollows it
 * is willing to infer from an absence. This writes the input to that inference: the raw list one
 * walk returned, stamped with when it was taken. The two disagree by design, and the disagreement
 * is worth keeping. A walk that stopped early retires nothing; a walk that would retire too much is
 * refused outright. In both cases the graph deliberately does not record what came back, and after
 * the fact there is no way to recover it. Here there is.
 *
 * <p><b>This must run after {@code FollowWriter}, not before.</b> {@code scrape_run_member} carries
 * a foreign key to {@code account}, and it is the follow writer that inserts the accounts a walk
 * saw. Called first, every row would fail that key. Both run inside {@code sync}'s single
 * transaction, so a snapshot and the graph it accompanies land together or not at all.
 *
 * <p>A run that never walked is still recorded, with {@code walked = false} and the reason. An
 * absent row would say "no sync happened", which is a different fact from "a sync happened and
 * declined to spend money on this account", and the second is the one worth being able to see.
 */
public final class ScrapeRunWriter {
    private final Connection connection;
    private final Logger logger;

    public ScrapeRunWriter(Connection connection, Logger logger) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Stores one walk's membership.
     *
     * @param seen the accounts the walk returned, in the order it returned them
     * @param diff what {@link FollowWriter} made of the same walk, for the counts on the run row
     * @return the new {@code scrape_run.id}
     */
    public long record(
            String subjectId,
            ConnectionType direction,
            List<InstagramAccount> seen,
            Instant startedAt,
            Instant finishedAt,
            boolean complete,
            FollowDiff diff) throws SQLException {

        long runId = insertRun(
                subjectId, direction, startedAt, finishedAt, true, null, complete,
                diff.seen(), diff.skippedNoId(), diff.added(), diff.retired());
        int stored = insertMembers(runId, seen);

        logger.info("Stored a run snapshot.", Map.of(
                "runId", runId,
                "accountId", subjectId,
                "direction", direction.name(),
                "members", stored,
                "complete", complete));
        return runId;
    }

    /** Records a run that looked at the account and did not walk it. */
    public long recordSkipped(
            String subjectId,
            ConnectionType direction,
            Instant startedAt,
            Instant finishedAt,
            String reason) throws SQLException {

        long runId = insertRun(
                subjectId, direction, startedAt, finishedAt, false, reason, false, 0, 0, 0, 0);
        logger.info("Recorded a run that did not walk.", Map.of(
                "runId", runId, "accountId", subjectId, "reason", reason));
        return runId;
    }

    private long insertRun(
            String subjectId, ConnectionType direction, Instant startedAt, Instant finishedAt,
            boolean walked, String skippedReason, boolean complete,
            int seen, int skippedNoId, int added, int retired) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO scrape_run
                    (subject_id, direction, started_at, finished_at, walked, skipped_reason,
                     complete, seen, skipped_no_id, added, retired)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id""")) {
            statement.setString(1, subjectId);
            statement.setString(2, direction.name());
            statement.setTimestamp(3, Timestamp.from(startedAt));
            statement.setTimestamp(4, Timestamp.from(finishedAt));
            statement.setBoolean(5, walked);
            statement.setString(6, skippedReason);
            statement.setBoolean(7, complete);
            statement.setInt(8, seen);
            statement.setInt(9, skippedNoId);
            statement.setInt(10, added);
            statement.setInt(11, retired);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Inserting a scrape_run returned no id.");
                }
                return results.getLong(1);
            }
        }
    }

    /**
     * One batch, deduplicated in memory first.
     *
     * <p>A source paginating a list that is changing under it can return the same account on two
     * pages, and within a single batch {@code ON CONFLICT DO NOTHING} does not save you: Postgres
     * refuses a command that touches the same row twice. Filtering here is what makes that
     * harmless, and it is also the honest count — the same account seen twice is one member.
     *
     * @return how many accounts were stored, which is below {@code seen.size()} when rows had no id
     */
    private int insertMembers(long runId, List<InstagramAccount> seen) throws SQLException {
        Set<String> ids = new HashSet<>();
        for (InstagramAccount account : seen) {
            if (account.id() != null && !account.id().isBlank()) {
                ids.add(account.id());
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO scrape_run_member (run_id, account_id) VALUES (?, ?)")) {
            for (String id : ids) {
                statement.setLong(1, runId);
                statement.setString(2, id);
                statement.addBatch();
            }
            int stored = 0;
            for (int updated : statement.executeBatch()) {
                if (updated == Statement.SUCCESS_NO_INFO || updated > 0) {
                    stored++;
                }
            }
            return stored;
        }
    }
}

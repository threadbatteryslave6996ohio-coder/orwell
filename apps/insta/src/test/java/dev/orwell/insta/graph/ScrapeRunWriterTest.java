package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.InstagramAccount;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The snapshot exists to answer what the graph cannot, so most of these tests are about the cases
 * where the two deliberately disagree: a walk whose diff was refused still has a snapshot, and an
 * incomplete walk is stored while being marked as the sample it is.
 */
class ScrapeRunWriterTest extends GraphTest {
    private static final String ME = "1000";

    @Test
    void storesEveryAccountAWalkReturned() throws Exception {
        long runId = walk(List.of(account("1", "alice"), account("2", "bob")), true);

        assertThat(members(runId)).containsExactlyInAnyOrder("1", "2");
        assertThat(count("scrape_run")).isEqualTo(1);
    }

    /** The timestamp is the point of the table: a snapshot with no instant is not a snapshot. */
    @Test
    void stampsTheRunWithWhenTheWalkRanAndFinished() throws Exception {
        seed("1");
        Instant started = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant finished = started.plus(90, ChronoUnit.SECONDS);

        writer().record(
                ME, ConnectionType.FOLLOWERS, List.of(account("1", "alice")),
                started, finished, true, diff(1, 0, 1, 0));

        assertThat(instant("SELECT started_at FROM scrape_run")).isEqualTo(started);
        assertThat(instant("SELECT finished_at FROM scrape_run")).isEqualTo(finished);
    }

    /**
     * Two runs over the same account are two snapshots, not one updated in place. Without this the
     * table would be another current-state view and would answer nothing follow_edge does not.
     */
    @Test
    void keepsEachRunSeparately() throws Exception {
        long first = walk(List.of(account("1", "alice"), account("2", "bob")), true);
        long second = walk(List.of(account("1", "alice")), true);

        assertThat(first).isNotEqualTo(second);
        assertThat(members(first)).containsExactlyInAnyOrder("1", "2");
        assertThat(members(second)).containsExactly("1");
    }

    // ─────────────────────────────────────────────── where the graph and the snapshot diverge

    /**
     * The case this table was added for. The follow writer refuses to retire on an incomplete walk,
     * so the graph keeps accounts that walk never saw; the snapshot records what it actually
     * returned, and `complete` marks it as a sample so nobody reads it as a census.
     */
    @Test
    void storesAnIncompleteWalkAndSaysItWasIncomplete() throws Exception {
        long runId = walk(List.of(account("1", "alice")), false);

        assertThat(members(runId)).containsExactly("1");
        assertThat(strings("SELECT complete::text FROM scrape_run")).containsExactly("false");
    }

    /** An account with no id cannot be keyed, so it is absent — and the count says how many. */
    @Test
    void skipsAccountsWithNoIdAndRecordsHowMany() throws Exception {
        seed("1");
        long runId = writer().record(
                ME, ConnectionType.FOLLOWERS,
                List.of(account("1", "alice"), account(null, "ghost")),
                Instant.now().minusSeconds(1), Instant.now(), true, diff(1, 1, 1, 0));

        assertThat(members(runId)).containsExactly("1");
        assertThat(strings("SELECT skipped_no_id::text FROM scrape_run")).containsExactly("1");
    }

    /**
     * A source paginating a shifting list can return the same account twice. Postgres refuses a
     * single command that touches one row twice, so this would fail the whole sync transaction if
     * the duplicate were not filtered before the batch.
     */
    @Test
    void storesAnAccountOnceWhenAWalkReturnedItTwice() throws Exception {
        long runId = walk(
                List.of(account("1", "alice"), account("2", "bob"), account("1", "alice")), true);

        assertThat(members(runId)).containsExactlyInAnyOrder("1", "2");
    }

    // ─────────────────────────────────────────────────────────────────── runs that did not walk

    @Test
    void recordsARunThatSkippedTheWalkWithItsReason() throws Exception {
        seed();

        writer().recordSkipped(
                ME, ConnectionType.FOLLOWERS, Instant.now().minusSeconds(2), Instant.now(),
                "the account is private");

        assertThat(strings("SELECT walked::text FROM scrape_run")).containsExactly("false");
        assertThat(strings("SELECT skipped_reason FROM scrape_run"))
                .containsExactly("the account is private");
        assertThat(count("scrape_run_member")).isZero();
    }

    // ────────────────────────────────────────────────────────────────────────────────── helpers

    /** Runs the follow writer first, as sync does — the snapshot's rows point at `account`. */
    private long walk(List<InstagramAccount> seen, boolean complete) throws SQLException {
        Instant started = Instant.now().minusSeconds(1);
        Instant finished = Instant.now();
        FollowDiff diff = new FollowWriter(
                connection, accounts(), FollowWriter.DEFAULT_MAX_RETIRE_FRACTION, NO_OP_LOGGER)
                .record(ME, ConnectionType.FOLLOWERS, seen, started, finished, complete);
        return writer().record(
                ME, ConnectionType.FOLLOWERS, seen, started, finished, complete, diff);
    }

    /**
     * Account rows for the cases that call the run writer without a walk in front of it. The
     * foreign keys are the reason: in {@code sync} the follow writer has already inserted every
     * account by the time a snapshot is written, and these tests stand in for that.
     */
    private void seed(String... accountIds) throws SQLException {
        AccountWriter accounts = accounts();
        accounts.upsertAccount(ME, Instant.now());
        for (String id : accountIds) {
            accounts.upsertAccount(id, Instant.now());
        }
    }

    private AccountWriter accounts() {
        return new AccountWriter(connection, url -> {
            throw new UnsupportedOperationException("no pictures in this test");
        }, new DisabledPictureStore(), NO_OP_LOGGER);
    }

    private ScrapeRunWriter writer() {
        return new ScrapeRunWriter(connection, NO_OP_LOGGER);
    }

    private List<String> members(long runId) throws SQLException {
        return strings(
                "SELECT account_id FROM scrape_run_member WHERE run_id = ? ORDER BY account_id",
                runId);
    }

    private static FollowDiff diff(int seen, int skippedNoId, int added, int retired) {
        return new FollowDiff(seen, skippedNoId, added, retired, null);
    }

    private static InstagramAccount account(String id, String username) {
        return new InstagramAccount(id, username, null, null, null, null);
    }
}

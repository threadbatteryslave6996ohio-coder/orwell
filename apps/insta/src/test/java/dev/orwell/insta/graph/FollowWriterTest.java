package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.InstagramAccount;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The diff — the part of this feature that is actually hard.
 *
 * <p>Recording follows is bookkeeping. Deciding that an <em>absence</em> means an unfollow is an
 * inference, and every test below is about when that inference is allowed to run. Getting it wrong
 * does not produce a wrong number in a report; it produces thousands of alerts telling someone
 * their friends left.
 */
class FollowWriterTest extends GraphTest {
    private static final String ME = "1000";

    @Test
    void recordsNewFollowersAsEdges() throws Exception {
        FollowDiff diff = walk(List.of(account("1", "alice"), account("2", "bob")), true);

        assertThat(diff.seen()).isEqualTo(2);
        assertThat(diff.added()).isEqualTo(2);
        assertThat(followers()).containsExactlyInAnyOrder("1", "2");
    }

    /** Every account seen becomes a row, even ones we know nothing else about. */
    @Test
    void createsAnAccountRowForEveryFollower() throws Exception {
        walk(List.of(account("1", "alice")), true);

        assertThat(count("account")).isEqualTo(2);          // the follower and the subject
        assertThat(strings("SELECT username FROM account_username WHERE account_id = '1'"))
                .containsExactly("alice");
    }

    @Test
    void countsAnAlreadyKnownFollowerAsSeenButNotAdded() throws Exception {
        walk(List.of(account("1", "alice")), true);

        FollowDiff second = walk(List.of(account("1", "alice")), true);

        assertThat(second.seen()).isEqualTo(1);
        assertThat(second.added()).isZero();
        assertThat(count("follow_edge")).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────── the inference

    @Test
    void retiresAFollowerACompleteWalkNoLongerSees() throws Exception {
        walkYesterday(List.of(account("1", "alice"), account("2", "bob")));

        FollowDiff diff = walk(List.of(account("1", "alice")), true);

        assertThat(diff.retired()).isEqualTo(1);
        assertThat(diff.retirementRan()).isTrue();
        assertThat(liveFollowers()).containsExactly("1");
        assertThat(instant("SELECT lost_at FROM follow_edge WHERE follower_id = '2'")).isNotNull();
    }

    /**
     * The one that matters most. A walk that stopped early has not seen the accounts it did not
     * reach — treating them as departures would report a truncated page as a mass exodus.
     */
    @Test
    void retiresNothingWhenTheWalkDidNotFinish() throws Exception {
        walkYesterday(List.of(account("1", "alice"), account("2", "bob")));

        FollowDiff diff = walk(List.of(account("1", "alice")), false);

        assertThat(diff.retired()).isZero();
        assertThat(diff.retirementRan()).isFalse();
        assertThat(diff.retirementSkipped()).contains("did not reach the end");
        assertThat(liveFollowers()).containsExactlyInAnyOrder("1", "2");
    }

    /** A row with no id cannot be stored, and an unstorable row is indistinguishable from absence. */
    @Test
    void retiresNothingWhenSomeRowsHadNoId() throws Exception {
        walkYesterday(List.of(account("1", "alice"), account("2", "bob")));

        FollowDiff diff = walk(List.of(account("1", "alice"), account(null, "ghost")), true);

        assertThat(diff.skippedNoId()).isEqualTo(1);
        assertThat(diff.seen()).isEqualTo(1);
        assertThat(diff.retired()).isZero();
        assertThat(diff.retirementSkipped()).contains("no id");
        assertThat(liveFollowers()).containsExactlyInAnyOrder("1", "2");
    }

    /**
     * A private or deleted account returns an empty list, which reads exactly like everyone
     * leaving at once. Past a threshold the writer refuses to believe itself.
     */
    @Test
    void refusesToRetireImplausiblyManyAtOnce() throws Exception {
        List<InstagramAccount> crowd = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            crowd.add(account(String.valueOf(index), "user" + index));
        }
        walkYesterday(crowd);

        FollowDiff diff = walk(List.of(), true);

        assertThat(diff.retired()).isZero();
        assertThat(diff.retirementSkipped()).contains("would retire 100 of 100");
        assertThat(liveFollowers()).hasSize(100);
    }

    /** Below the floor the percentage is meaningless — losing 1 of 3 followers is ordinary. */
    @Test
    void stillRetiresForSmallAccountsWhereAFractionWouldBeMisleading() throws Exception {
        walkYesterday(List.of(account("1", "a"), account("2", "b"), account("3", "c")));

        FollowDiff diff = walk(List.of(account("1", "a")), true);

        assertThat(diff.retired()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────── re-follows

    @Test
    void bringsBackAnEdgeWhenSomeoneFollowsAgain() throws Exception {
        walkAt(YESTERDAY, List.of(account("1", "alice"), account("2", "bob")), true);
        walkAt(YESTERDAY.plusSeconds(3600), List.of(account("1", "alice")), true);
        assertThat(instant("SELECT lost_at FROM follow_edge WHERE follower_id = '2'")).isNotNull();

        walk(List.of(account("1", "alice"), account("2", "bob")), true);

        assertThat(instant("SELECT lost_at FROM follow_edge WHERE follower_id = '2'")).isNull();
        assertThat(liveFollowers()).containsExactlyInAnyOrder("1", "2");
    }

    /**
     * The subtle one: a resurrected edge that kept its notification stamp would look
     * already-alerted, and the <em>next</em> unfollow would silently notify nobody.
     */
    @Test
    void clearsTheNotificationStampWhenAnEdgeComesBack() throws Exception {
        walkAt(YESTERDAY, List.of(account("1", "alice"), account("2", "bob")), true);
        walkAt(YESTERDAY.plusSeconds(3600), List.of(account("1", "alice")), true);
        markNotified("2");
        assertThat(instant("SELECT unfollow_notified_at FROM follow_edge WHERE follower_id = '2'"))
                .isNotNull();

        walk(List.of(account("1", "alice"), account("2", "bob")), true);

        assertThat(instant("SELECT unfollow_notified_at FROM follow_edge WHERE follower_id = '2'"))
                .isNull();
    }

    // ─────────────────────────────────────────────────────────── direction

    /** Walking someone's "following" puts them on the other end of the edge. */
    @Test
    void orientsTheEdgeByTheDirectionWalked() throws Exception {
        writer().record(ME, ConnectionType.FOLLOWING, List.of(account("1", "alice")),
                Instant.now().minus(1, ChronoUnit.MINUTES), Instant.now(), true);

        assertThat(strings("SELECT followee_id FROM follow_edge")).containsExactly("1");
        assertThat(strings("SELECT follower_id FROM follow_edge")).containsExactly(ME);
    }

    /** One direction's walk must not retire the other's edges. */
    @Test
    void doesNotRetireEdgesBelongingToTheOtherDirection() throws Exception {
        walkYesterday(List.of(account("1", "alice")));
        writer().record(ME, ConnectionType.FOLLOWING, List.of(account("2", "bob")),
                YESTERDAY.minusSeconds(1), YESTERDAY, true);

        walk(List.of(account("1", "alice")), true);

        assertThat(instant("SELECT lost_at FROM follow_edge WHERE followee_id = '2'")).isNull();
    }

    // ─────────────────────────────────────────────────────────── helpers

    private static final Instant YESTERDAY =
            Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    /** An earlier walk, whose edges a later one can find stale. */
    private FollowDiff walkYesterday(List<InstagramAccount> seen) throws SQLException {
        return walkAt(YESTERDAY, seen, true);
    }

    private FollowDiff walk(List<InstagramAccount> seen, boolean complete) throws SQLException {
        return walkAt(Instant.now(), seen, complete);
    }

    /**
     * The watermark sits just before this walk began — later than anything an earlier walk wrote,
     * earlier than anything this one writes. That ordering is the whole mechanism: get it wrong and
     * either nothing is ever retired, or a walk retires the rows it just inserted.
     */
    private FollowDiff walkAt(Instant at, List<InstagramAccount> seen, boolean complete)
            throws SQLException {
        return writer().record(
                ME, ConnectionType.FOLLOWERS, seen, at.minusSeconds(1), at, complete);
    }

    private FollowWriter writer() {
        AccountWriter accounts = new AccountWriter(
                connection, url -> {
            throw new UnsupportedOperationException("no pictures in this test");
        }, new DisabledPictureStore(), NO_OP_LOGGER);
        return new FollowWriter(
                connection, accounts, FollowWriter.DEFAULT_MAX_RETIRE_FRACTION, NO_OP_LOGGER);
    }

    private void markNotified(String followerId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE follow_edge SET unfollow_notified_at = now() WHERE follower_id = ?")) {
            statement.setString(1, followerId);
            statement.executeUpdate();
        }
    }

    private List<String> followers() throws SQLException {
        return strings("SELECT follower_id FROM follow_edge WHERE followee_id = ?", ME);
    }

    private List<String> liveFollowers() throws SQLException {
        return strings(
                "SELECT follower_id FROM follow_edge WHERE followee_id = ? AND lost_at IS NULL", ME);
    }

    private static InstagramAccount account(String id, String username) {
        return new InstagramAccount(id, username, null, null, null, null);
    }
}

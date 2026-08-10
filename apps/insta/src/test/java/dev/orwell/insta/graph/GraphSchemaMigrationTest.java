package dev.orwell.insta.graph;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Upgrading a database that already carries the old {@code follow_edge} — keyed on the pair, with
 * {@code lost_at} and {@code unfollow_notified_at} on the row.
 *
 * <p>A migration nobody has run is a migration that does not work, and this one has to survive
 * being applied to a live database rather than a fresh one. Writing it caught an ordering bug on
 * its own: the partial index on {@code active} sat above the block that adds the column, so the
 * whole schema failed on exactly the databases the migration exists for.
 */
class GraphSchemaMigrationTest extends GraphTest {

    @Test
    void upgradesAnOldDatabaseAndKeepsTheHistoryItCouldHold() throws Exception {
        givenTheOldShape();

        GraphSchema.apply(connection);

        // The pair is now a unique constraint over a surrogate key.
        assertThat(strings("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'follow_edge' ORDER BY column_name"))
                .containsExactly("active", "first_seen_at", "followee_id", "follower_id", "id",
                        "last_seen_at");

        assertThat(strings("SELECT follower_id FROM follow_edge WHERE active ORDER BY follower_id"))
                .containsExactly("stayed");
        assertThat(strings("SELECT follower_id FROM follow_edge WHERE NOT active"))
                .containsExactly("left");

        // One follow per edge, at the moment it was first seen.
        assertThat(strings("""
                SELECT e.follower_id FROM follows f JOIN follow_edge e ON e.id = f.edge_id
                ORDER BY e.follower_id""")).containsExactly("left", "stayed");

        // The one departure the old shape could still describe, notification stamp intact.
        assertThat(strings("""
                SELECT e.follower_id, u.notified_at IS NOT NULL FROM unfollows u
                JOIN follow_edge e ON e.id = u.edge_id""")).containsExactly("left");
        assertThat(count("unfollows")).isEqualTo(1);
    }

    /** Applying it twice must not migrate twice, or the backfill would duplicate every row. */
    @Test
    void isSafeToApplyAgain() throws Exception {
        givenTheOldShape();
        GraphSchema.apply(connection);

        assertThatCode(() -> GraphSchema.apply(connection)).doesNotThrowAnyException();

        assertThat(count("follows")).isEqualTo(2);
        assertThat(count("unfollows")).isEqualTo(1);
    }

    /** A fresh database must not enter the migration block at all. */
    @Test
    void leavesAFreshDatabaseAlone() throws Exception {
        GraphSchema.apply(connection);

        assertThat(count("follow_edge")).isZero();
        assertThat(count("follows")).isZero();
        assertThat(count("unfollows")).isZero();
    }

    private void givenTheOldShape() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    DROP TABLE IF EXISTS post_media, post_metric, post_caption, post, unfollows,
                        follows, follow_edge, account_profile_picture, account_bio,
                        account_username, account CASCADE;

                    CREATE TABLE account (
                        id    TEXT PRIMARY KEY,
                        added TIMESTAMPTZ NOT NULL DEFAULT now()
                    );
                    CREATE TABLE follow_edge (
                        followee_id          TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                        follower_id          TEXT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
                        first_seen_at        TIMESTAMPTZ NOT NULL,
                        last_seen_at         TIMESTAMPTZ NOT NULL,
                        lost_at              TIMESTAMPTZ,
                        unfollow_notified_at TIMESTAMPTZ,
                        PRIMARY KEY (followee_id, follower_id)
                    );

                    INSERT INTO account (id) VALUES ('me'), ('stayed'), ('left');
                    INSERT INTO follow_edge
                        (followee_id, follower_id, first_seen_at, last_seen_at, lost_at,
                         unfollow_notified_at)
                    VALUES
                        ('me', 'stayed', now() - interval '10 days', now(), NULL, NULL),
                        ('me', 'left',   now() - interval '10 days', now() - interval '2 days',
                         now() - interval '1 day', now() - interval '1 day');
                    """);
        }
    }
}

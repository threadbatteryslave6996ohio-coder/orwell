package dev.orwell.insta.ui;

import dev.orwell.insta.graph.GraphTest;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the viewer reads. Read-only, so the tests are about shape and scope rather than effects. */
class GraphQueriesTest extends GraphTest {

    @Test
    void resolvesAnAccountByAnyHandleItHasUsed() throws Exception {
        given();

        assertThat(GraphQueries.resolve(connection, "nasa")).contains("me");
        assertThat(GraphQueries.resolve(connection, "@NASA")).contains("me");   // pasted and cased
        assertThat(GraphQueries.resolve(connection, "old_nasa")).contains("me"); // a former handle
        assertThat(GraphQueries.resolve(connection, "me")).contains("me");       // the id itself
        assertThat(GraphQueries.resolve(connection, "nobody")).isEmpty();
    }

    /** The picker offers accounts a sync has walked, not everyone who turned up as a follower. */
    @Test
    void listsOnlyAccountsWithFollowersRecorded() throws Exception {
        given();

        List<GraphView.Node> accounts = GraphQueries.walkedAccounts(connection);

        assertThat(accounts).extracting(GraphView.Node::id).containsExactly("me");
        assertThat(accounts.get(0).username()).isEqualTo("nasa");
        assertThat(accounts.get(0).followers()).isEqualTo(2);   // carol has left
    }

    @Test
    void returnsTheSubjectAndItsNeighboursAsNodesAndLinks() throws Exception {
        given();

        GraphView view = GraphQueries.neighbourhood(connection, "me", false);

        assertThat(view.nodes()).extracting(GraphView.Node::id)
                .containsExactlyInAnyOrder("me", "alice", "bob");
        assertThat(view.nodes()).filteredOn(GraphView.Node::subject)
                .extracting(GraphView.Node::username).containsExactly("nasa");
        assertThat(view.links()).allMatch(GraphView.Link::active);
    }

    /** Departed followers are hidden by default and shown on request — the checkbox in the page. */
    @Test
    void includesDepartedFollowersOnlyWhenAsked() throws Exception {
        given();

        assertThat(GraphQueries.neighbourhood(connection, "me", false).nodes())
                .extracting(GraphView.Node::id).doesNotContain("carol");
        assertThat(GraphQueries.neighbourhood(connection, "me", true).nodes())
                .extracting(GraphView.Node::id).contains("carol");
        assertThat(GraphQueries.neighbourhood(connection, "me", true).links())
                .anyMatch(link -> !link.active());
    }

    /**
     * The part that makes it a web: an edge between two of the subject's followers shows up once
     * one of them has been walked too.
     */
    @Test
    void includesFollowsBetweenNeighbours() throws Exception {
        given();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO follow_edge (followee_id, follower_id, first_seen_at, last_seen_at)
                    VALUES ('alice', 'bob', now(), now())""");
        }

        GraphView view = GraphQueries.neighbourhood(connection, "me", false);

        assertThat(view.links())
                .anyMatch(l -> l.followee().equals("alice") && l.follower().equals("bob"));
    }

    /** An account nobody follows is a node on its own, not an error. */
    @Test
    void handlesAnAccountWithNoEdges() throws Exception {
        given();

        GraphView view = GraphQueries.neighbourhood(connection, "alice", false);

        assertThat(view.nodes()).extracting(GraphView.Node::id).contains("alice");
    }

    @Test
    void reportsRecentDeparturesNewestFirstWithTheirRepeatCount() throws Exception {
        given();

        List<Map<String, Object>> departures = GraphQueries.recentUnfollows(connection, "me", 10);

        assertThat(departures).hasSize(1);
        assertThat(departures.get(0)).containsEntry("username", "carol")
                .containsEntry("timesLeft", 1)
                .containsEntry("returned", false);
    }

    /** me: followed by alice and bob; carol has left. me was once called old_nasa. */
    private void given() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO account (id) VALUES ('me'), ('alice'), ('bob'), ('carol');
                    INSERT INTO account_username (account_id, username, first_seen_at, last_seen_at)
                    VALUES ('me', 'old_nasa', now() - interval '9 days', now() - interval '5 days'),
                           ('me', 'nasa', now() - interval '4 days', now()),
                           ('alice', 'alice', now(), now()),
                           ('bob', 'bob', now(), now()),
                           ('carol', 'carol', now(), now());
                    INSERT INTO follow_edge
                        (followee_id, follower_id, active, first_seen_at, last_seen_at)
                    VALUES ('me', 'alice', TRUE, now(), now()),
                           ('me', 'bob', TRUE, now(), now()),
                           ('me', 'carol', FALSE, now() - interval '9 days',
                            now() - interval '2 days');
                    INSERT INTO unfollows (edge_id, at)
                    SELECT id, now() - interval '1 day' FROM follow_edge
                    WHERE follower_id = 'carol';
                    """);
        }
    }
}

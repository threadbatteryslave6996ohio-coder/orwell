package dev.orwell.insta.ui;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read side. Every query here is against the graph {@code insta sync} writes, and none of them
 * write anything — the UI can only ever show you what a sync already collected.
 */
public final class GraphQueries {

    private GraphQueries() {
    }

    /**
     * Resolves what somebody typed into an account id, accepting either the id itself or any handle
     * the account has ever used. Handles are matched case-insensitively and most-recent-first,
     * because a released handle can belong to more than one account over time.
     */
    public static Optional<String> resolve(Connection connection, String accountOrUsername)
            throws SQLException {
        String input = accountOrUsername.trim();
        if (input.startsWith("@")) {
            input = input.substring(1);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id FROM account_username
                WHERE lower(username) = lower(?) ORDER BY last_seen_at DESC LIMIT 1""")) {
            statement.setString(1, input);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    return Optional.of(results.getString(1));
                }
            }
        }
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT id FROM account WHERE id = ?")) {
            statement.setString(1, input);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * Accounts worth offering in the picker: the ones a sync has actually walked, which is to say
     * the ones with followers recorded. An account that only appears as somebody else's follower
     * has no graph of its own to show.
     */
    public static List<GraphView.Node> walkedAccounts(Connection connection) throws SQLException {
        List<GraphView.Node> accounts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.followee_id AS id, count(*) FILTER (WHERE e.active) AS followers,
                       (SELECT u.username FROM account_username u
                        WHERE u.account_id = e.followee_id
                        ORDER BY u.last_seen_at DESC LIMIT 1) AS username
                FROM follow_edge e
                GROUP BY e.followee_id
                ORDER BY followers DESC""");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                accounts.add(new GraphView.Node(
                        results.getString("id"),
                        results.getString("username"),
                        false,
                        results.getInt("followers")));
            }
        }
        return accounts;
    }

    /**
     * The neighbourhood of one account: everyone joined to it in either direction, plus any follows
     * among those neighbours that we happen to know about.
     *
     * <p>That last part is what makes it a web rather than a star. We only learn an edge between
     * two of your followers by having synced one of them, so the picture fills in as more accounts
     * are walked — and stays a plain star until then, honestly reflecting what has been collected.
     */
    public static GraphView neighbourhood(Connection connection, String subjectId, boolean includeInactive)
            throws SQLException {
        String activeOnly = includeInactive ? "" : " AND e.active";

        Map<String, GraphView.Node> nodes = new LinkedHashMap<>();
        List<GraphView.Link> links = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement("""
                WITH scope AS (
                    SELECT ?::text AS id
                    UNION
                    SELECT follower_id FROM follow_edge WHERE followee_id = ?
                    UNION
                    SELECT followee_id FROM follow_edge WHERE follower_id = ?
                )
                SELECT e.followee_id, e.follower_id, e.active FROM follow_edge e
                WHERE e.followee_id IN (SELECT id FROM scope)
                  AND e.follower_id IN (SELECT id FROM scope)""" + activeOnly)) {
            statement.setString(1, subjectId);
            statement.setString(2, subjectId);
            statement.setString(3, subjectId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    links.add(new GraphView.Link(
                            results.getString("followee_id"),
                            results.getString("follower_id"),
                            results.getBoolean("active")));
                }
            }
        }

        List<String> ids = new ArrayList<>();
        ids.add(subjectId);
        for (GraphView.Link link : links) {
            ids.add(link.followee());
            ids.add(link.follower());
        }
        for (String id : ids.stream().distinct().toList()) {
            nodes.put(id, null);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id,
                       (SELECT u.username FROM account_username u
                        WHERE u.account_id = a.id ORDER BY u.last_seen_at DESC LIMIT 1) AS username,
                       (SELECT count(*) FROM follow_edge e
                        WHERE e.followee_id = a.id AND e.active) AS followers
                FROM account a WHERE a.id = ANY (?)""")) {
            statement.setArray(1, connection.createArrayOf(
                    "text", nodes.keySet().toArray(new String[0])));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String id = results.getString("id");
                    nodes.put(id, new GraphView.Node(
                            id,
                            results.getString("username"),
                            id.equals(subjectId),
                            results.getInt("followers")));
                }
            }
        }
        nodes.values().removeIf(java.util.Objects::isNull);
        return new GraphView(List.copyOf(nodes.values()), links);
    }

    /**
     * Everything worth showing about one account when you click its node: who it is now, who it
     * used to be, what it says about itself, and — when a subject is given — the history of that
     * one relationship.
     *
     * <p>Most of these fields are empty for the outer ring of the graph. An account seen only as
     * somebody else's follower has a handle and nothing more, because nothing has ever run a
     * profile lookup against it. The card says so rather than rendering a row of zeroes.
     */
    public static Map<String, Object> accountCard(
            Connection connection, String accountId, String subjectId) throws SQLException {
        Map<String, Object> card = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, a.added,
                       (SELECT u.username FROM account_username u WHERE u.account_id = a.id
                        ORDER BY u.last_seen_at DESC LIMIT 1) AS username,
                       (SELECT b.bio FROM account_bio b WHERE b.account_id = a.id
                        ORDER BY b.last_seen_at DESC LIMIT 1) AS bio,
                       (SELECT count(*) FROM follow_edge e
                        WHERE e.followee_id = a.id AND e.active) AS followers,
                       (SELECT count(*) FROM follow_edge e
                        WHERE e.follower_id = a.id AND e.active) AS following,
                       (SELECT count(*) FROM post p WHERE p.account_id = a.id) AS posts,
                       (SELECT count(*) FROM follow_edge e WHERE e.followee_id = a.id) AS walked
                FROM account a WHERE a.id = ?""")) {
            statement.setString(1, accountId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Map.of();
                }
                card.put("id", results.getString("id"));
                card.put("username", results.getString("username"));
                card.put("bio", results.getString("bio"));
                card.put("followers", results.getInt("followers"));
                card.put("following", results.getInt("following"));
                card.put("posts", results.getInt("posts"));
                card.put("walked", results.getInt("walked") > 0);
                card.put("firstSeen", String.valueOf(results.getTimestamp("added").toInstant()));
            }
        }

        // Former handles, so a rename is visible rather than silently replacing the old name.
        card.put("formerUsernames", strings(connection, """
                SELECT username FROM account_username WHERE account_id = ?
                  AND username <> COALESCE((SELECT u.username FROM account_username u
                      WHERE u.account_id = ? ORDER BY u.last_seen_at DESC LIMIT 1), '')
                ORDER BY last_seen_at DESC""", accountId, accountId));

        if (subjectId != null && !subjectId.equals(accountId)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.active, e.first_seen_at,
                           (SELECT count(*) FROM unfollows u WHERE u.edge_id = e.id) AS times_left
                    FROM follow_edge e
                    WHERE e.followee_id = ? AND e.follower_id = ?""")) {
                statement.setString(1, subjectId);
                statement.setString(2, accountId);
                try (ResultSet results = statement.executeQuery()) {
                    if (results.next()) {
                        Map<String, Object> relation = new LinkedHashMap<>();
                        relation.put("follows", results.getBoolean("active"));
                        relation.put("since",
                                String.valueOf(results.getTimestamp("first_seen_at").toInstant()));
                        relation.put("timesLeft", results.getInt("times_left"));
                        card.put("relation", relation);
                    }
                }
            }
        }
        return card;
    }

    private static List<String> strings(Connection connection, String sql, String... parameters)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    values.add(results.getString(1));
                }
            }
        }
        return values;
    }

    /** The most recent departures, newest first — the thing you actually open this to look at. */
    public static List<Map<String, Object>> recentUnfollows(
            Connection connection, String subjectId, int limit) throws SQLException {
        List<Map<String, Object>> departures = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT u.at,
                       (SELECT un.username FROM account_username un
                        WHERE un.account_id = e.follower_id
                        ORDER BY un.last_seen_at DESC LIMIT 1) AS username,
                       e.follower_id,
                       (SELECT count(*) FROM unfollows u2 WHERE u2.edge_id = e.id) AS times_left,
                       e.active
                FROM unfollows u JOIN follow_edge e ON e.id = u.edge_id
                WHERE e.followee_id = ?
                ORDER BY u.at DESC LIMIT ?""")) {
            statement.setString(1, subjectId);
            statement.setInt(2, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("at", String.valueOf(results.getTimestamp("at").toInstant()));
                    row.put("username", results.getString("username"));
                    row.put("accountId", results.getString("follower_id"));
                    row.put("timesLeft", results.getInt("times_left"));
                    // A departure on a currently-active edge means they came back afterwards.
                    row.put("returned", results.getBoolean("active"));
                    departures.add(row);
                }
            }
        }
        return departures;
    }
}

package dev.orwell.insta.graph;

import dev.orwell.testing.PostgresJdbcTest;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixture for the graph tests: a real Postgres, a fresh schema per test, and small helpers
 * for asking the database what it ended up believing.
 *
 * <p>Real Postgres rather than an in-memory stand-in because the behaviour under test <em>is</em>
 * the SQL — upsert conflict targets, the {@code xmax = 0} new-row trick, partial indexes. None of
 * that survives translation to another dialect.
 */
public abstract class GraphTest extends PostgresJdbcTest {
    public static final Logger NO_OP_LOGGER = entry -> {
    };

    protected Connection connection;

    @BeforeEach
    void openSchema() throws SQLException {
        connection = connection();
        // Every table the schema creates, or rows leak between tests through the shared container.
        // Adding a table to schema.sql means adding it here too.
        try (PreparedStatement drop = connection.prepareStatement(
                "DROP TABLE IF EXISTS post_media, post_metric, post_caption, post, unfollows,"
                        + " follows, follow_edge, scrape_run_member, scrape_run, scrape_block,"
                        + " account_profile_picture, account_bio, account_username,"
                        + " account CASCADE")) {
            drop.execute();
        }
        GraphSchema.apply(connection);
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    protected int count(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet results = statement.executeQuery()) {
            return results.next() ? results.getInt(1) : 0;
        }
    }

    protected List<String> strings(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (results.next()) {
                    values.add(results.getString(1));
                }
                return values;
            }
        }
    }

    protected Instant instant(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next() || results.getTimestamp(1) == null) {
                    return null;
                }
                return results.getTimestamp(1).toInstant();
            }
        }
    }
}

package dev.orwell.insta.graph;

import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
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
@Testcontainers
abstract class GraphTest {
    protected static final Logger NO_OP_LOGGER = entry -> {
    };

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected Connection connection;

    @BeforeEach
    void openSchema() throws SQLException {
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (PreparedStatement drop = connection.prepareStatement(
                "DROP TABLE IF EXISTS follow_edge, account_profile_picture, account_bio,"
                        + " account_username, account CASCADE")) {
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

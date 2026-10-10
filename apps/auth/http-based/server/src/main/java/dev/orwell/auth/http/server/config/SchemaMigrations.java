package dev.orwell.auth.http.server.config;

import dev.orwell.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * The mechanics every startup migration in this service shares, so each one is only the two things
 * that differ: how to tell it is needed, and the SQL.
 *
 * <p>Hibernate's {@code ddl-auto: update} cannot add a {@code NOT NULL} column to a table that
 * already has rows — it logs the rejection, carries on, and the next query fails on a column that
 * does not exist. That is why these run at all, and why they run before the JPA bootstrap.
 *
 * @see WorkspaceSchemaMigration
 * @see ClientTokenSchemaMigration
 */
final class SchemaMigrations {

    /** A {@code needed?} test that may talk to the database, which a plain {@code Predicate} cannot. */
    @FunctionalInterface
    interface Check {
        boolean isNeeded(Statement statement) throws SQLException;
    }

    private SchemaMigrations() {
    }

    /**
     * Runs {@code statements} when {@code needed} says so, in one transaction, and says nothing at
     * all when it does not — the common case is a database that is already current, on every boot.
     *
     * <p><strong>One transaction, because Postgres can roll DDL back.</strong> A half-applied
     * migration — the column added but still nullable — is the state that is genuinely hard to
     * recover from, and the one Hibernate would then disagree with on every startup.
     *
     * <p><strong>A failure is fatal, on purpose.</strong> Continuing means Hibernate meets an
     * unmigrated table and the server answers 500s for every request that touches it; a startup
     * failure naming the migration is the cheaper outcome.
     *
     * @param description what is being migrated, for the log record and the failure message
     */
    static void applyIfNeeded(
            DataSource dataSource, Logger logger, String description, Check needed, List<String> statements) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            if (!needed.isNeeded(statement)) {
                return;
            }

            logger.warn("Applying a schema migration.", Map.of("migration", description));

            connection.setAutoCommit(false);
            try {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

            logger.info("Applied a schema migration.", Map.of("migration", description));
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not apply the schema migration: " + description, exception);
        }
    }

    /**
     * Catalog lookups, interpolated rather than bound: an identifier cannot be a bind parameter in
     * these queries. Every caller passes a compile-time constant from this package — no value here
     * ever comes from a request.
     */
    static boolean tableExists(Statement statement, String table) throws SQLException {
        return queryExists(statement, """
                SELECT 1 FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = '%s'
                """.formatted(table));
    }

    static boolean columnExists(Statement statement, String table, String column) throws SQLException {
        return queryExists(statement, """
                SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = '%s'
                  AND column_name = '%s'
                """.formatted(table, column));
    }

    static boolean indexExists(Statement statement, String table, String index) throws SQLException {
        return queryExists(statement, """
                SELECT 1 FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = '%s'
                  AND indexname = '%s'
                """.formatted(table, index));
    }

    static boolean queryExists(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next();
        }
    }
}

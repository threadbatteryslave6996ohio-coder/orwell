package dev.orwell.auth.http.server.config;

import dev.orwell.logging.Logger;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Adds {@code client_tokens.last_used_at} to a database whose tokens predate usage tracking,
 * before Hibernate looks at the table.
 *
 * <p>Needed for the same reason {@link WorkspaceSchemaMigration} is: {@code ddl-auto: update}
 * cannot add a {@code NOT NULL} column to a table that already has rows — it logs the rejection,
 * carries on, and the first query then fails on a column that does not exist.
 *
 * <p><strong>Existing rows are backfilled to the migration's own clock, not to {@code created_at}.
 * </strong> That is the whole point of doing this in a migration rather than letting the column be
 * nullable and reading {@code COALESCE(last_used_at, created_at)}. Before this feature there was no
 * record of use, so a token issued a year ago and used every minute since is indistinguishable from
 * one abandoned the day it was minted. Backfilling to now gives every pre-existing token one full
 * idle period of grace, during which anything actually in use records a use and takes itself out of
 * the sweep. Writing {@code created_at} instead would make the first sweep after an upgrade delete
 * live credentials — the case being a second process still holding a token its own client already
 * replaced. And because the grace is a stored timestamp rather than something derived from process
 * start, a server that restarts hourly neither re-arms it nor postpones the collection forever.
 *
 * <p>Every statement is idempotent and the whole thing is skipped on a database with no such table
 * yet, where Hibernate creates the current schema unaided. Postgres-specific, like the rest of this
 * service's persistence. It can be deleted once no deployment predates the column.
 */
@Configuration
public class ClientTokenSchemaMigration {

    @Bean
    ClientTokenMigrationRunner clientTokenMigrationRunner(DataSource dataSource, Logger logger) {
        return new ClientTokenMigrationRunner(dataSource, logger);
    }

    /**
     * Makes the JPA bootstrap wait for this migration too. The post-processor appends to whatever
     * dependencies are already registered, so this and {@link WorkspaceSchemaMigration}'s do not
     * displace each other.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnClientTokenMigration() {
        return new EntityManagerFactoryDependsOnPostProcessor("clientTokenMigrationRunner");
    }

    public static class ClientTokenMigrationRunner {
        private static final String TABLE = "client_tokens";
        private static final String COLUMN = "last_used_at";
        private static final String INDEX = "idx_client_tokens_identity_created";

        private final DataSource dataSource;
        private final Logger logger;

        ClientTokenMigrationRunner(DataSource dataSource, Logger logger) {
            this.dataSource = dataSource;
            this.logger = logger;
        }

        @PostConstruct
        public void migrate() {
            SchemaMigrations.applyIfNeeded(
                    dataSource, logger, TABLE + "." + COLUMN, ClientTokenMigrationRunner::needsMigration, statements());
        }

        private static List<String> statements() {
            return List.of(
                    "ALTER TABLE client_tokens ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP(6) WITH TIME ZONE",
                    // now(), not created_at — see the class comment. This is the grace period.
                    "UPDATE client_tokens SET last_used_at = now() WHERE last_used_at IS NULL",
                    "ALTER TABLE client_tokens ALTER COLUMN last_used_at SET NOT NULL",
                    // Declared on the entity as well, for a database Hibernate creates from
                    // scratch. Stated here too because ddl-auto is not guaranteed to add an index
                    // to a table that already exists, and the cleanup job's correlated subquery is
                    // a sequential scan per candidate row without it.
                    "CREATE INDEX IF NOT EXISTS " + INDEX + " "
                            + "ON client_tokens (client_identity_id, created_at)");
        }

        /**
         * Both halves are checked, not just the column — the same reason
         * {@link WorkspaceSchemaMigration} checks two. This migration adds an index as well, so
         * treating the column as the done-marker would strand a database that has {@code
         * last_used_at} without {@code idx_client_tokens_identity_created} — the shape a failed
         * earlier run leaves, and the shape Hibernate leaves on a table it created before the
         * index was declared on the entity. The cleanup job's correlated subquery would stay a
         * sequential scan per candidate row, forever, with nothing saying so.
         */
        private static boolean needsMigration(Statement statement) throws SQLException {
            return SchemaMigrations.tableExists(statement, TABLE)
                    && (!SchemaMigrations.columnExists(statement, TABLE, COLUMN)
                            || !SchemaMigrations.indexExists(statement, TABLE, INDEX));
        }
    }
}

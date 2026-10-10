package dev.orwell.auth.http.server.config;

import dev.orwell.auth.http.api.AuthWorkspaces;
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
 * Brings a pre-workspace {@code client_identities} table up to the current shape, before Hibernate
 * looks at it.
 *
 * <p>{@code ddl-auto: update} cannot do this on its own, and the failure is not theoretical: adding
 * a {@code NOT NULL} column to a table that already has rows is rejected, Hibernate logs that and
 * carries on, and the first query then fails with "column workspace does not exist". It also never
 * drops the old single-column {@code UNIQUE (client_id)}, which would keep forbidding the same
 * {@code clientId} in two workspaces — the one thing workspaces exist to allow.
 *
 * <p>Every statement is idempotent and the whole thing is skipped on a database that has no such
 * table yet, where Hibernate creates the right schema unaided. Postgres-specific, like the rest of
 * this service's persistence. It can be deleted once no deployment predates workspaces.
 */
@Configuration
public class WorkspaceSchemaMigration {

    @Bean
    WorkspaceMigrationRunner workspaceMigrationRunner(DataSource dataSource, Logger logger) {
        return new WorkspaceMigrationRunner(dataSource, logger);
    }

    /**
     * Makes the JPA bootstrap wait for the migration. Without this the two race, and Hibernate
     * usually wins — which is exactly the state this class exists to prevent.
     */
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor entityManagerFactoryDependsOnWorkspaceMigration() {
        return new EntityManagerFactoryDependsOnPostProcessor("workspaceMigrationRunner");
    }

    public static class WorkspaceMigrationRunner {
        private static final String TABLE = "client_identities";

        private final DataSource dataSource;
        private final Logger logger;

        WorkspaceMigrationRunner(DataSource dataSource, Logger logger) {
            this.dataSource = dataSource;
            this.logger = logger;
        }

        @PostConstruct
        public void migrate() {
            SchemaMigrations.applyIfNeeded(
                    dataSource, logger, TABLE + " → workspaces", WorkspaceMigrationRunner::needsMigration, statements());
        }

        /**
         * Both halves are checked, not just the column: a database that has {@code workspace} but
         * still carries the old {@code UNIQUE (client_id)} is the shape a previously failed run
         * leaves behind, and treating the column as the done-marker would strand it there forever.
         */
        private static boolean needsMigration(Statement statement) throws SQLException {
            return SchemaMigrations.tableExists(statement, TABLE)
                    && (!SchemaMigrations.columnExists(statement, TABLE, "workspace")
                            || legacyConstraintExists(statement));
        }

        private static List<String> statements() {
            return List.of(
                    "ALTER TABLE client_identities ADD COLUMN IF NOT EXISTS workspace VARCHAR(64)",
                    // Existing identities are the ones clients already look for at the default
                    // workspace, so that is where they belong.
                    "UPDATE client_identities SET workspace = '" + AuthWorkspaces.DEFAULT + "' WHERE workspace IS NULL",
                    "ALTER TABLE client_identities ALTER COLUMN workspace SET NOT NULL",
                    // Dropped by shape, not by name: the old constraint was created by Hibernate,
                    // so on a real database it is called something like `uka02adod2efk6c0j0s4qd9`.
                    // Anything unique on client_id alone has to go, whatever it ended up named.
                    """
                            DO $$
                            DECLARE constraint_record record;
                            BEGIN
                                FOR constraint_record IN
                                    SELECT con.conname FROM pg_constraint con
                                    JOIN pg_class rel ON rel.oid = con.conrelid
                                    WHERE rel.relname = 'client_identities'
                                      AND con.contype = 'u'
                                      AND (
                                          SELECT array_agg(att.attname::text)
                                          FROM unnest(con.conkey) AS key
                                          JOIN pg_attribute att
                                            ON att.attrelid = con.conrelid AND att.attnum = key
                                      ) = ARRAY['client_id']
                                LOOP
                                    EXECUTE format(
                                        'ALTER TABLE client_identities DROP CONSTRAINT %I', constraint_record.conname);
                                END LOOP;
                            END $$;
                            """,
                    """
                            DO $$ BEGIN
                                IF NOT EXISTS (
                                    SELECT 1 FROM pg_constraint
                                    WHERE conname = 'uk_client_identities_workspace_client_id'
                                ) THEN
                                    ALTER TABLE client_identities
                                        ADD CONSTRAINT uk_client_identities_workspace_client_id
                                        UNIQUE (workspace, client_id);
                                END IF;
                            END $$;
                            """);
        }

        /** A unique constraint on {@code client_id} alone — whatever Hibernate happened to name it. */
        private static boolean legacyConstraintExists(Statement statement) throws SQLException {
            return SchemaMigrations.queryExists(statement, """
                    SELECT 1 FROM pg_constraint con
                    JOIN pg_class rel ON rel.oid = con.conrelid
                    WHERE rel.relname = 'client_identities'
                      AND con.contype = 'u'
                      AND (
                          SELECT array_agg(att.attname::text)
                          FROM unnest(con.conkey) AS key
                          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = key
                      ) = ARRAY['client_id']
                    """);
        }
    }
}

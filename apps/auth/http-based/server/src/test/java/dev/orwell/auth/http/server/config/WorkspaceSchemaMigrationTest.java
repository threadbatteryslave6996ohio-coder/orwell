package dev.orwell.auth.http.server.config;

import dev.orwell.auth.http.server.config.WorkspaceSchemaMigration.WorkspaceMigrationRunner;
import dev.orwell.logging.Logger;
import dev.orwell.testing.PostgresJdbcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the migration against a real Postgres, because everything that makes it necessary is
 * database behaviour: {@code NOT NULL} on a populated table, a unique constraint under a name
 * Hibernate chose, and the catalog queries used to find both.
 *
 * <p>Not a {@code PostgresIntegrationTest} subclass — that base wires a Spring context with
 * {@code ddl-auto: create-drop}, which would build the *new* schema and leave nothing to migrate.
 * This drives the runner directly against the legacy table instead.
 */
class WorkspaceSchemaMigrationTest extends PostgresJdbcTest {

    private final Logger logger = entry -> {
    };

    @BeforeEach
    void dropEverything() throws SQLException {
        execute("DROP TABLE IF EXISTS client_tokens", "DROP TABLE IF EXISTS client_identities");
    }

    @Test
    void backfillsExistingIdentitiesIntoTheDefaultWorkspace() throws SQLException {
        givenLegacySchema();
        execute("INSERT INTO client_identities (client_id, secret_hash, created_at, active) "
                + "VALUES ('client-a', 'hash', now(), true)");

        migrate();

        assertEquals(List.of("def"), query("SELECT workspace FROM client_identities"));
    }

    /** The reason a plain {@code ddl-auto: update} cannot do this: the table already has rows. */
    @Test
    void makesTheColumnNotNullOnAPopulatedTable() throws SQLException {
        givenLegacySchema();
        execute("INSERT INTO client_identities (client_id, secret_hash, created_at, active) "
                + "VALUES ('client-a', 'hash', now(), true)");

        migrate();

        assertEquals(List.of("NO"), query("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'client_identities' AND column_name = 'workspace'
                """));
    }

    @Test
    void replacesTheOldClientIdConstraintSoOneNameCanExistInTwoWorkspaces() throws SQLException {
        givenLegacySchema();
        execute("INSERT INTO client_identities (client_id, secret_hash, created_at, active) "
                + "VALUES ('client-a', 'hash', now(), true)");

        migrate();

        execute("INSERT INTO client_identities (workspace, client_id, secret_hash, created_at, active) "
                + "VALUES ('team-a', 'client-a', 'hash', now(), true)");
        assertEquals(2, query("SELECT client_id FROM client_identities").size());

        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO client_identities (workspace, client_id, secret_hash, created_at, active) "
                        + "VALUES ('team-a', 'client-a', 'hash', now(), true)"),
                "the composite constraint must still forbid a duplicate within one workspace");
    }

    /** The state a failed earlier run leaves: column present, old constraint still there. */
    @Test
    void finishesAPartiallyMigratedTable() throws SQLException {
        givenLegacySchema();
        execute("ALTER TABLE client_identities ADD COLUMN workspace VARCHAR(64)");

        migrate();

        assertTrue(query("""
                SELECT conname FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
                WHERE rel.relname = 'client_identities' AND con.contype = 'u'
                """).contains("uk_client_identities_workspace_client_id"));
        assertFalse(query("""
                SELECT conname FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid
                WHERE rel.relname = 'client_identities' AND con.contype = 'u'
                """).contains("uka02adod2efk6c0j0s4qd9kic6"));
    }

    @Test
    void doesNothingWhenThereIsNoTableYet() {
        migrate();

        assertEquals(List.of(), query("""
                SELECT table_name FROM information_schema.tables WHERE table_name = 'client_identities'
                """));
    }

    /** Running twice must be as harmless as running once — it runs on every startup. */
    @Test
    void isSafeToRunAgain() throws SQLException {
        givenLegacySchema();
        execute("INSERT INTO client_identities (client_id, secret_hash, created_at, active) "
                + "VALUES ('client-a', 'hash', now(), true)");

        migrate();
        migrate();

        assertEquals(List.of("def"), query("SELECT workspace FROM client_identities"));
    }

    /** The table as Hibernate created it before workspaces, random constraint name included. */
    private void givenLegacySchema() throws SQLException {
        execute("""
                CREATE TABLE client_identities (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    client_id VARCHAR(128) NOT NULL,
                    secret_hash VARCHAR(512) NOT NULL,
                    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                    active BOOLEAN NOT NULL,
                    CONSTRAINT uka02adod2efk6c0j0s4qd9kic6 UNIQUE (client_id)
                )
                """);
    }

    private void migrate() {
        new WorkspaceMigrationRunner(dataSource(), logger).migrate();
    }
}

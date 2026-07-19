package dev.orwell.auth.http.server;

import dev.orwell.logging.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDatabaseNoticeTest {
    private final List<LogEntry> entries = new ArrayList<>();

    @Test
    void logsThroughTheInjectedLoggerForALocalUrl() {
        new LocalDatabaseNotice(entries::add, "jdbc:postgresql://localhost:5432/auth").run(null);

        assertEquals(1, entries.size());
        assertEquals("Using local database.", entries.getFirst().message());
        assertEquals(
                "jdbc:postgresql://localhost:5432/auth",
                entries.getFirst().metadata().get("datasourceUrl"));
    }

    @Test
    void staysQuietForARemoteUrl() {
        new LocalDatabaseNotice(entries::add, "jdbc:postgresql://db.example.com:5432/auth").run(null);

        assertTrue(entries.isEmpty());
    }

    @Test
    void staysQuietWhenNoDatasourceUrlIsConfigured() {
        new LocalDatabaseNotice(entries::add, "").run(null);
        new LocalDatabaseNotice(entries::add, null).run(null);

        assertTrue(entries.isEmpty());
    }

    @Test
    void recognisesTheLocalHostForms() {
        assertTrue(LocalDatabaseNotice.isLocalDatabaseUrl("JDBC:POSTGRESQL://LOCALHOST/auth"));
        assertTrue(LocalDatabaseNotice.isLocalDatabaseUrl("  jdbc:postgresql://127.0.0.1/auth  "));
        assertTrue(LocalDatabaseNotice.isLocalDatabaseUrl("jdbc:postgresql://auth-postgres/auth"));
        assertFalse(LocalDatabaseNotice.isLocalDatabaseUrl("jdbc:postgresql://prod.internal/auth"));
    }
}

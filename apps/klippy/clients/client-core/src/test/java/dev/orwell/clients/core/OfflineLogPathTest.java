package dev.orwell.clients.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineLogPathTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesAPreRenameLogForward() throws IOException {
        Path legacy = tempDir.resolve("clippy-offline-clipboard.json");
        Path current = tempDir.resolve("klippy-offline-clipboard.json");
        Files.writeString(legacy, "{\"unsynced\":true}");

        OfflineLogPath.migrateLegacyIfPresent(legacy, current);

        assertFalse(Files.exists(legacy), "legacy log should be moved, not copied");
        assertEquals("{\"unsynced\":true}", Files.readString(current));
    }

    @Test
    void migratesTheDeadLetterSiblingToo() throws IOException {
        Path legacy = tempDir.resolve("clippy-offline-clipboard.json");
        Path current = tempDir.resolve("klippy-offline-clipboard.json");
        Path legacyDeadLetter = tempDir.resolve("clippy-offline-clipboard-dead-letter.json");
        Files.writeString(legacy, "entries");
        Files.writeString(legacyDeadLetter, "rejections");

        OfflineLogPath.migrateLegacyIfPresent(legacy, current);

        assertEquals("rejections", Files.readString(tempDir.resolve("klippy-offline-clipboard-dead-letter.json")));
        assertFalse(Files.exists(legacyDeadLetter));
    }

    @Test
    void neverClobbersAnExistingCurrentLog() throws IOException {
        Path legacy = tempDir.resolve("clippy-offline-clipboard.json");
        Path current = tempDir.resolve("klippy-offline-clipboard.json");
        Files.writeString(legacy, "stale");
        Files.writeString(current, "live");

        OfflineLogPath.migrateLegacyIfPresent(legacy, current);

        assertEquals("live", Files.readString(current), "current log must win");
        assertTrue(Files.exists(legacy), "legacy log stays put rather than being discarded");
    }

    @Test
    void isANoOpWhenThereIsNothingToMigrate() {
        Path legacy = tempDir.resolve("clippy-offline-clipboard.json");
        Path current = tempDir.resolve("klippy-offline-clipboard.json");

        OfflineLogPath.migrateLegacyIfPresent(legacy, current);

        assertFalse(Files.exists(current));
    }

    @Test
    void isIdempotentAcrossRestarts() throws IOException {
        Path legacy = tempDir.resolve("clippy-offline-clipboard.json");
        Path current = tempDir.resolve("klippy-offline-clipboard.json");
        Files.writeString(legacy, "entries");

        OfflineLogPath.migrateLegacyIfPresent(legacy, current);
        OfflineLogPath.migrateLegacyIfPresent(legacy, current);

        assertEquals("entries", Files.readString(current));
    }

    @Test
    void derivesTheDeadLetterName() {
        assertEquals(
                tempDir.resolve("klippy-offline-clipboard-dead-letter.json"),
                OfflineLogPath.deadLetterFor(tempDir.resolve("klippy-offline-clipboard.json")));
    }

    @Test
    void derivesTheDeadLetterNameWithoutAnExtension() {
        assertEquals(
                tempDir.resolve("offline-dead-letter.json"),
                OfflineLogPath.deadLetterFor(tempDir.resolve("offline")));
    }

    @Test
    void defaultsAreTheCurrentAndPreRenameNames() {
        assertEquals(Path.of("klippy-offline-clipboard.json"), OfflineLogPath.DEFAULT);
        assertEquals(Path.of("clippy-offline-clipboard.json"), OfflineLogPath.LEGACY);
    }
}

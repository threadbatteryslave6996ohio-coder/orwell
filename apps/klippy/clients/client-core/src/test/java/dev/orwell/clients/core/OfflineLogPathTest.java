package dev.orwell.clients.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfflineLogPathTest {
    @TempDir
    Path tempDir;

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
    void defaultIsTheOfflineLogName() {
        assertEquals(Path.of("klippy-offline-clipboard.json"), OfflineLogPath.DEFAULT);
    }
}

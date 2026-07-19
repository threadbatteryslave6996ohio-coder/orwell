package dev.orwell.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonLineFileWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsRatherThanOverwritingExistingContent() throws IOException {
        Path logFile = tempDir.resolve("app.jsonl");
        Files.writeString(logFile, "{\"message\":\"pre-existing entry\"}\n");

        new JsonLineFileWriter(logFile).write(Map.of("message", "new entry"));

        // Regression: opening with CREATE alone neither truncates nor seeks to end, so the new
        // line was written over the start of the old one, corrupting both.
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("pre-existing entry"), lines.get(0));
        assertTrue(lines.get(1).contains("new entry"), lines.get(1));
    }

    @Test
    void recreatesTheFileWhenARotatorHasMovedItAway() throws IOException {
        Path logFile = tempDir.resolve("app.jsonl");
        JsonLineFileWriter writer = new JsonLineFileWriter(logFile);
        writer.write(Map.of("message", "before rotation"));

        Files.move(logFile, tempDir.resolve("app.jsonl.1"));

        // Regression: opening with APPEND alone threw NoSuchFileException here, so every entry
        // written between rotation and restart was lost.
        writer.write(Map.of("message", "after rotation"));

        assertEquals(List.of("after rotation"), Files.readAllLines(logFile).stream()
                .map(line -> line.replaceAll(".*\"message\":\"([^\"]+)\".*", "$1"))
                .toList());
    }
}

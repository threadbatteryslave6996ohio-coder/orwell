package dev.orwell.server;

import dev.orwell.env.EnvFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClippyServerLauncherTest {
    @TempDir
    Path tempDir;

    @Test
    void launcherFetchesEnvironmentBeforePassingItToTheCore() throws Exception {
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/child"));
        Files.writeString(tempDir.resolve(".env"), "SERVER_PORT=9090\n");

        assertEquals("9090", EnvFiles.load(nestedDirectory).get("SERVER_PORT"));
    }
}

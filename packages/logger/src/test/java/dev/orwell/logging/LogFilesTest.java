package dev.orwell.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsToTheLogsDirectory() {
        withoutConfiguredDirectory(() ->
                assertEquals(Path.of("logs", "auth-server.jsonl"), LogFiles.resolve("auth-server", ".jsonl")));
    }

    @Test
    void derivesTheDirectoryFromTheConfiguredLogFile() {
        withoutConfiguredDirectory(() -> {
            LogFiles.configureDirectoryFromLogFile(tempDir.resolve("auth-server.log").toString());

            // Both sinks land beside Spring's own log file, which is the point of the shared helper.
            assertEquals(tempDir.resolve("auth-server.jsonl"), LogFiles.resolve("auth-server", ".jsonl"));
            assertEquals(tempDir.resolve("auth-server.txt"), LogFiles.resolve("auth-server", ".txt"));
        });
    }

    @Test
    void aLogFileWithNoParentFallsBackToTheWorkingDirectory() {
        withoutConfiguredDirectory(() -> {
            LogFiles.configureDirectoryFromLogFile("auth-server.log");
            assertEquals(Path.of("auth-server.jsonl"), LogFiles.resolve("auth-server", ".jsonl"));
        });
    }

    private void withoutConfiguredDirectory(Runnable body) {
        String original = System.getProperty(LogFiles.LOG_DIRECTORY_PROPERTY);
        System.clearProperty(LogFiles.LOG_DIRECTORY_PROPERTY);
        try {
            body.run();
        } finally {
            if (original == null) {
                System.clearProperty(LogFiles.LOG_DIRECTORY_PROPERTY);
            } else {
                System.setProperty(LogFiles.LOG_DIRECTORY_PROPERTY, original);
            }
        }
    }
}

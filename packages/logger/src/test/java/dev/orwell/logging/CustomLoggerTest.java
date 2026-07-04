package dev.orwell.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsNamedEntriesSeparatedByDashes() throws IOException {
        Path logPath = tempDir.resolve("auth-server.txt");
        String original = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            CustomLogger logger = new CustomLogger("auth-server");

            logger.log("Using local database");
            logger.log("Startup complete");

            String content = Files.readString(logPath);
            assertTrue(content.contains("[auth-server] [INFO] Using local database"));
            assertTrue(content.contains("-----"));
            assertTrue(content.contains("[auth-server] [INFO] Startup complete"));
        } finally {
            if (original == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", original);
            }
        }
    }

    @Test
    void logsViaInterface() throws IOException {
        Path logPath = tempDir.resolve("auth-server.txt");
        String original = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            Logger logger = new CustomLogger("auth-server");
            logger.log(new LogEntry(LogLevel.WARN, "Test warning", null));

            String content = Files.readString(logPath);
            assertTrue(content.contains("[auth-server] [WARN] Test warning"));
        } finally {
            if (original == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", original);
            }
        }
    }
}

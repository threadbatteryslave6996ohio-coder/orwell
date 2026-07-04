package dev.orwell.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void logsEntryWithLevelAndMessage() throws IOException {
        Path logPath = tempDir.resolve("test.txt");
        String original = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            Logger logger = new FileLogger("test");
            logger.log(new LogEntry(LogLevel.ERROR, "Something failed", null));

            String content = Files.readString(logPath);
            assertTrue(content.contains("[test] [ERROR] Something failed"));
        } finally {
            if (original == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", original);
            }
        }
    }

    @Test
    void logsEntryWithMetadata() throws IOException {
        Path logPath = tempDir.resolve("test.txt");
        String original = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            Logger logger = new FileLogger("test");
            logger.log(new LogEntry(LogLevel.INFO, "With metadata", Map.of("key", "value")));

            String content = Files.readString(logPath);
            assertTrue(content.contains("[test] [INFO] With metadata"));
            assertTrue(content.contains("key"));
            assertTrue(content.contains("value"));
        } finally {
            if (original == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", original);
            }
        }
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new FileLogger(""));
        assertThrows(IllegalArgumentException.class, () -> new FileLogger(null));
    }

    @Test
    void rejectsNullEntry() {
        FileLogger logger = new FileLogger("test");
        assertThrows(NullPointerException.class, () -> logger.log(null));
    }
}

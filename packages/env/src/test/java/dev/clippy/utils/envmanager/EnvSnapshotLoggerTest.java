package dev.clippy.utils.envmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvSnapshotLoggerTest {
    @TempDir
    Path tempDir;

    @Test
    void logsRedactedSnapshotToConsoleAndFile() throws Exception {
        EnvClassBuilder builder = EnvSchema.builder();
        EnvOption<String> remoteServerUrl = builder.required("REMOTE_SERVER_URL", EnvType.string());
        EnvOption<String> clientId = builder.required("CLIENT_ID", EnvType.string());
        EnvOption<String> clientSecret = builder.optional("CLIENT_SECRET", EnvType.string());
        EnvSchema schema = builder.build();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String originalLoggerDirectory = System.getProperty("custom.logger.dir");
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            Env env = schema.from(Map.of(
                    "REMOTE_SERVER_URL", "http://localhost:8080",
                    "CLIENT_ID", "dummy",
                    "CLIENT_SECRET", "super-secret"
            ), EnvSnapshotLogger.consoleAndFile("dummy-client-env"));

            assertEquals("http://localhost:8080", env.get(remoteServerUrl));
            assertEquals("dummy", env.get(clientId));
            assertEquals("super-secret", env.get(clientSecret));

            String console = output.toString(StandardCharsets.UTF_8);
            assertTrue(console.contains("[dummy-client-env] Loaded 3 environment values:"));
            assertTrue(console.contains("REMOTE_SERVER_URL=\"http://localhost:8080\""));
            assertTrue(console.contains("CLIENT_SECRET=[redacted]"));

            String file = Files.readString(tempDir.resolve("dummy-client-env.txt"));
            assertTrue(file.contains("[dummy-client-env] Loaded 3 environment values:"));
            assertTrue(file.contains("CLIENT_SECRET=[redacted]"));
        } finally {
            System.setOut(originalOut);
            if (originalLoggerDirectory == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDirectory);
            }
        }
    }

    @Test
    void logsToConsoleWithoutWritingFile() throws Exception {
        EnvClassBuilder builder = EnvSchema.builder();
        builder.required("REMOTE_SERVER_URL", EnvType.string());
        EnvSchema schema = builder.build();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        String originalLoggerDirectory = System.getProperty("custom.logger.dir");
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            schema.from(Map.of("REMOTE_SERVER_URL", "http://localhost:8080"),
                    EnvSnapshotLogger.consoleOnly("dummy-client-env"));

            String console = output.toString(StandardCharsets.UTF_8);
            assertTrue(console.contains("[dummy-client-env] Loaded 1 environment values:"));
            assertFalse(Files.exists(tempDir.resolve("dummy-client-env.txt")));
        } finally {
            System.setOut(originalOut);
            if (originalLoggerDirectory == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDirectory);
            }
        }
    }
}

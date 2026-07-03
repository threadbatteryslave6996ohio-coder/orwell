package dev.clippy.utils.envmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsDotenvFromAncestralDirectoryAndUnquotesValues() throws IOException {
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/child"));
        Files.writeString(
                tempDir.resolve(".env"),
                """
                        # comment
                        REMOTE_SERVER_URL = "http://localhost:8080"
                        CLIENT_ID='clippy-client'
                        CLIENT_TOKEN=token-123
                        EMPTY_LINE=
                        INVALID_LINE
                        """
        );

        Map<String, String> values = EnvFiles.load(nestedDirectory);

        assertEquals("http://localhost:8080", values.get("REMOTE_SERVER_URL"));
        assertEquals("clippy-client", values.get("CLIENT_ID"));
        assertEquals("token-123", values.get("CLIENT_TOKEN"));
    }

    @Test
    void loadsValuesFromExplicitDotenvFile() throws IOException {
        Path dotenvFile = tempDir.resolve("combined.env");
        Files.writeString(
                dotenvFile,
                """
                        COMBINED_SERVER_PORT=8080
                        CLIPPY_AUTH_ROUTE_PREFIX=/auth
                        """
        );

        Map<String, String> values = EnvFiles.loadFile(dotenvFile);

        assertEquals("8080", values.get("COMBINED_SERVER_PORT"));
        assertEquals("/auth", values.get("CLIPPY_AUTH_ROUTE_PREFIX"));
    }

    @Test
    void loadRequiredFileRejectsMissingFile() {
        Path dotenvFile = tempDir.resolve("missing.env");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> EnvFiles.loadRequiredFile(dotenvFile));

        assertEquals("Env file is not present: " + dotenvFile.toAbsolutePath().normalize(), exception.getMessage());
    }

    @Test
    void loadingDoesNotDependOnCustomLoggerDirectory() throws IOException {
        Path dotenvFile = tempDir.resolve("service.env");
        Path nonDirectoryLogTarget = tempDir.resolve("not-a-directory");
        Files.writeString(dotenvFile, "SERVER_PORT=9090\n");
        Files.writeString(nonDirectoryLogTarget, "file");
        String originalLoggerDirectory = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", nonDirectoryLogTarget.toString());

        try {
            assertEquals("9090", EnvFiles.loadRequiredFile(dotenvFile).get("SERVER_PORT"));
        } finally {
            if (originalLoggerDirectory == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDirectory);
            }
        }
    }
}

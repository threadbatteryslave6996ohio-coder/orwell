package dev.clippy.combined;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CombinedServerLauncherTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesEnvironmentAndPassesFileValuesWithoutCoreDefaults() throws Exception {
        Path envFile = tempDir.resolve("combined.env");
        Files.writeString(envFile, "COMBINED_SERVER_PORT=9090\n");

        Map<String, String> values = CombinedServerLauncher.resolveEnvironment(
                Map.of("CLIPPY_ENV_FILE", envFile.toString()),
                Path.of("/unused")
        );

        assertThat(values).containsExactly(Map.entry("COMBINED_SERVER_PORT", "9090"));
    }

    @Test
    void processEnvironmentOverridesTheSelectedFile() throws Exception {
        Path envFile = tempDir.resolve("combined.env");
        Files.writeString(envFile, "COMBINED_SERVER_PORT=9090\n");

        Map<String, String> values = CombinedServerLauncher.resolveEnvironment(
                Map.of(
                        "CLIPPY_ENV_FILE", envFile.toString(),
                        "COMBINED_SERVER_PORT", "9191"
                ),
                Path.of("/unused")
        );

        assertThat(values).containsEntry("COMBINED_SERVER_PORT", "9191");
    }

    @Test
    void defaultEnvFileResolvesFromTheCombinedServerModuleDirectory() throws Exception {
        Path codeSourceLocation = tempDir.resolve("combined-server/target/classes");
        Path envFile = tempDir.resolve("combined-server/.env");
        Files.createDirectories(envFile.getParent());
        Files.writeString(envFile, "COMBINED_SERVER_PORT=8080\n");

        assertThat(CombinedServerLauncher.defaultEnvFile(codeSourceLocation)).isEqualTo(envFile);
    }

    @Test
    void defaultEnvFileReportsSimpleActionableErrorWhenModuleDirectoryCannotBeResolved() {
        assertThatThrownBy(() -> CombinedServerLauncher.defaultEnvFile(Path.of("/")))
                .isInstanceOf(IOException.class)
                .hasMessage("Missing combined server env file. Create combined-server/.env or set CLIPPY_ENV_FILE to its absolute path.");
    }

    @Test
    void startupErrorMessageUsesSingleConcisePrefix() {
        IOException error = new IOException("env file missing");

        assertThat(CombinedServerLauncher.startupErrorMessage(error))
                .isEqualTo("Combined server startup error: env file missing");
    }
}

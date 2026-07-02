package dev.clippy.combined;

import dev.clippy.utils.envmanager.EnvFiles;
import dev.clippy.utils.envmanager.EnvValidationException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Resolves external configuration and passes it into the combined-server core. */
public final class CombinedServerLauncher {
    private static final String ENV_FILE_VARIABLE = "CLIPPY_ENV_FILE";

    private CombinedServerLauncher() {
    }

    public static void main(String[] args) {
        try {
            CombinedServerApplication.start(resolveEnvironment());
        } catch (IOException | IllegalStateException | EnvValidationException e) {
            System.err.println(startupErrorMessage(e));
            System.exit(1);
        }
    }

    static Map<String, String> resolveEnvironment() throws IOException {
        try {
            Path codeSourceLocation = Path.of(
                    CombinedServerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            return resolveEnvironment(System.getenv(), codeSourceLocation);
        } catch (URISyntaxException e) {
            throw new IOException(missingEnvFileMessage(), e);
        }
    }

    static Map<String, String> resolveEnvironment(
            Map<String, String> processEnvironment,
            Path codeSourceLocation
    ) throws IOException {
        String explicitFile = processEnvironment.get(ENV_FILE_VARIABLE);
        Path envFile = explicitFile != null && !explicitFile.isBlank()
                ? Path.of(explicitFile.trim())
                : defaultEnvFile(codeSourceLocation);
        Map<String, String> values = new HashMap<>(EnvFiles.loadRequiredFile(envFile));
        processEnvironment.forEach((name, value) -> {
            if (!ENV_FILE_VARIABLE.equals(name) && value != null && !value.isBlank()) {
                values.put(name, value);
            }
        });
        return Map.copyOf(values);
    }

    static Path defaultEnvFile(Path codeSourceLocation) throws IOException {
        Path moduleDirectory = codeSourceLocation.getParent() == null
                ? null
                : codeSourceLocation.getParent().getParent();
        if (moduleDirectory == null) {
            throw new IOException(missingEnvFileMessage());
        }
        Path envFile = moduleDirectory.resolve(".env");
        if (!Files.isRegularFile(envFile)) {
            throw new IOException(missingEnvFileMessage());
        }
        return envFile;
    }

    static String startupErrorMessage(Exception error) {
        return "Combined server startup error: " + error.getMessage();
    }

    private static String missingEnvFileMessage() {
        return "Missing combined server env file. Create combined-server/.env or set CLIPPY_ENV_FILE to its absolute path.";
    }
}

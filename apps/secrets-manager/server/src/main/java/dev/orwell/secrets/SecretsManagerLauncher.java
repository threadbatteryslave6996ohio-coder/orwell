package dev.orwell.secrets;

import dev.orwell.env.EnvFiles;
import dev.orwell.env.http.EnvLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class SecretsManagerLauncher {
    private SecretsManagerLauncher() {
    }

    public static void main(String[] args) throws IOException {
        SecretsManagerApplication.start(EnvLoader.load("file"));
    }

    static Map<String, String> resolveEnvironment(Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

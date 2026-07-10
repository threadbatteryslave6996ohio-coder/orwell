package dev.orwell.secrets;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.env.EnvFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public final class SecretsManagerLauncher {
    private SecretsManagerLauncher() {
    }

    public static void main(String[] args) {
        new AppServer(SecretsManagerEnvs.ENV, SecretsManagerApplication::start)
                .runOrExit(args);
    }

    static Map<String, String> resolveEnvironment(Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

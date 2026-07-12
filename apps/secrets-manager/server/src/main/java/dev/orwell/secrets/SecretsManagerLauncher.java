package dev.orwell.secrets;

import dev.orwell.bootstrap.AppServer;

public final class SecretsManagerLauncher {
    private SecretsManagerLauncher() {
    }

    public static void main(String[] args) {
        new AppServer(SecretsManagerEnvs.ENV, SecretsManagerApplication::start)
                .runOrExit(args);
    }
}

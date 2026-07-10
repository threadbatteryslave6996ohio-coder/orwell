package dev.orwell.auth.http.server;

import dev.orwell.auth.http.server.config.AuthServerEnvs;
import dev.orwell.bootstrap.AppServer;
import dev.orwell.env.EnvFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Default standalone entry point for the auth server. This is the "actor" that decides how the
 * environment is fetched (here: a {@code .env} file discovered from the working directory) and hands
 * the resolved values to {@link ClippyAuthServerApplication#start(Map)}. Swap this class
 * for a different strategy (system env, Vault, k8s, ...) without touching the core.
 */
public final class ClippyAuthServerLauncher {
    private ClippyAuthServerLauncher() {
    }

    public static void main(String[] args) {
        new AppServer(AuthServerEnvs.ENV, ClippyAuthServerApplication::start)
                .runOrExit(args);
    }

    public static Map<String, String> resolveEnvironment(Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

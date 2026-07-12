package dev.orwell.server.application;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.server.config.ServerEnvs;

import java.util.Map;

/**
 * Default standalone entry point for the clipboard server. This is the "actor" that decides how the
 * environment is fetched (here: a {@code .env} file discovered from the working directory) and hands
 * the resolved values to {@link ClippyServerApplication#start(Map)}. Swap this class for a
 * different strategy (system env, Vault, k8s, ...) without touching the core.
 */
public final class ClippyServerLauncher {
    private ClippyServerLauncher() {
    }

    public static void main(String[] args) {
        new AppServer(ServerEnvs.ENV, ClippyServerApplication::start)
                .runOrExit(args);
    }
}

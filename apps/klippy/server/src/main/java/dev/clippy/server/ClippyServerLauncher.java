package dev.clippy.server;

import dev.clippy.utils.envmanager.EnvFiles;

import java.io.IOException;
import java.nio.file.Path;
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

    public static void main(String[] args) throws IOException {
        ClippyServerApplication.start(EnvFiles.load());
    }

    static Map<String, String> resolveEnvironment(Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

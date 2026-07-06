package dev.orwell.server;

import dev.orwell.env.EnvFiles;
import dev.orwell.env.http.EnvLoader;

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

    public static void main(String[] args) {
        try {
            ClippyServerApplication.start(resolveEnv(args));
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, String> resolveEnv(String[] args) throws IOException {
        if (args.length > 0 && "--remote".equals(args[0])) {
            String url = args.length > 1 ? args[1] : "http://localhost:8080/v1/env";
            return EnvLoader.fetchRemote(url);
        }
        return EnvLoader.load("file");
    }

    static Map<String, String> resolveEnvironment(Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

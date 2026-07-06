package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.http.EnvLoader;

import java.io.IOException;
import java.util.Map;

public class ChatServer {
    public static void main(String[] args) {
        try {
            var env = KeeboarderEnvs.from(resolveEnv(args));
            SpringServerBootstrap.run(
                    KeeboarderServerApplication.class,
                    KeeboarderEnvs.springProperties(env),
                    "keeboarderServerLauncher");
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

    static Map<String, String> resolveEnvironment(java.nio.file.Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

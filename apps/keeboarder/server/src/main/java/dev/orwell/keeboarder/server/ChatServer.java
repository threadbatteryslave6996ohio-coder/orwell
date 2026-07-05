package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.EnvFiles;

import java.io.IOException;

public class ChatServer {
    public static void main(String[] args) throws IOException {
        var env = KeeboarderEnvs.from(EnvFiles.load());
        SpringServerBootstrap.run(
                KeeboarderServerApplication.class,
                KeeboarderEnvs.springProperties(env),
                "keeboarderServerLauncher");
    }

    static java.util.Map<String, String> resolveEnvironment(java.nio.file.Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

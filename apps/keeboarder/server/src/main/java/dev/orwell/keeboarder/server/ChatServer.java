package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.env.EnvFiles;

import java.io.IOException;
import java.util.Map;

public class ChatServer {
    public static void main(String[] args) {
        new AppServer(KeeboarderEnvs.ENV, KeeboarderServerApplication::start)
                .runOrExit(args);
    }

    static Map<String, String> resolveEnvironment(java.nio.file.Path startDirectory) throws IOException {
        return EnvFiles.load(startDirectory);
    }
}

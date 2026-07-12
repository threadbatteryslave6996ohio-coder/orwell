package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.keeboarder.server.config.KeeboarderEnvs;

public class ChatServer {
    public static void main(String[] args) {
        new AppServer(KeeboarderEnvs.ENV, KeeboarderServerApplication::start)
                .runOrExit(args);
    }
}

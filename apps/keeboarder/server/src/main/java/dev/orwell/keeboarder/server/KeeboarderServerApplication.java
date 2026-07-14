package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.keeboarder.server.config.KeeboarderEnvs;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Keeboarder server: REST API plus the WebSocket chat endpoint on the embedded container. */
@SpringBootApplication
public class KeeboarderServerApplication {
    /**
     * Server descriptor: how the environment is fetched stays with whoever calls
     * {@code SERVER.start(...)} / {@code runOrExit}; the core never reads {@code .env} files itself.
     */
    public static final AppServer SERVER = AppServer.spring(KeeboarderServerApplication.class)
            .name("keeboarder-server")
            .envs(KeeboarderEnvs.ENV)
            .properties(KeeboarderEnvs::springProperties)
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}

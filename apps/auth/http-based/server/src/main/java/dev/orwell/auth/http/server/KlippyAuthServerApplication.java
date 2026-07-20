package dev.orwell.auth.http.server;

import dev.orwell.auth.http.server.config.AuthServerEnvs;
import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KlippyAuthServerApplication {
    /**
     * Server descriptor: how the environment is fetched stays with whoever calls
     * {@code SERVER.start(...)} / {@code runOrExit}; the core never reads {@code .env} files itself.
     */
    public static final AppServer SERVER = new AppServer(
            KlippyAuthServerApplication.class,
            "auth-server",
            AuthServerEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}

package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Embeddable Keeboarder HTTP application; process startup lives in {@link ChatServer}. */
@SpringBootApplication
public class KeeboarderServerApplication {
    public static ConfigurableApplicationContext start(Env env) {
        return SpringServerBootstrap.run(
                KeeboarderServerApplication.class,
                KeeboarderEnvs.springProperties(env),
                "keeboarderServerLauncher");
    }
}

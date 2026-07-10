package dev.orwell.keeboarder.server;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import dev.orwell.keeboarder.server.config.KeeboarderEnvs;
import dev.orwell.logging.CustomLogger;
import dev.orwell.logging.Logger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/** Embeddable Keeboarder HTTP application; process startup lives in {@link ChatServer}. */
@SpringBootApplication
public class KeeboarderServerApplication {
    public static ConfigurableApplicationContext start(Env env) {
        return SpringServerBootstrap.run(
                KeeboarderServerApplication.class,
                KeeboarderEnvs.springProperties(env),
                "keeboarderServerLauncher");
    }

    /** Custom {@link Logger} available for injection across the Keeboarder server. */
    @Bean
    public Logger logger() {
        return new CustomLogger("keeboarder-server");
    }
}

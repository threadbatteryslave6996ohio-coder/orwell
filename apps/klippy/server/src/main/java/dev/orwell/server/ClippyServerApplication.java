package dev.orwell.server;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import dev.orwell.logging.CustomLogger;
import dev.orwell.logging.Logger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@SpringBootApplication
public class ClippyServerApplication {
    public static ConfigurableApplicationContext start(Env env) {
        return SpringServerBootstrap.start(
                ClippyServerApplication.class,
                env.get(ServerEnvs.LOGGING_FILE_NAME),
                ServerEnvs.springProperties(env),
                "clippyServerLauncher");
    }

    /**
     * Boots the clipboard server from an already-resolved environment. The core never reads
     * {@code .env} files or system env itself: whoever launches it (see {@link ClippyServerLauncher},
     * the combined server, or tests) decides how to fetch the values and passes them in here.
     */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        return start(ServerEnvs.from(environment));
    }

    /** Custom {@link Logger} available for injection across the clipboard server. */
    @Bean
    public Logger logger() {
        return new CustomLogger("clippy-server");
    }
}

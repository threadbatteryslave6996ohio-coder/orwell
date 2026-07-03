package dev.clippy.server;

import dev.clippy.bootstrap.SpringServerBootstrap;
import dev.clippy.utils.envmanager.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

@SpringBootApplication
public class ClippyServerApplication {
    /**
     * Boots the clipboard server from an already-resolved environment. The core never reads
     * {@code .env} files or system env itself: whoever launches it (see {@link ClippyServerLauncher},
     * the combined server, or tests) decides how to fetch the values and passes them in here.
     */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        Env env = ServerEnvs.from(environment);
        return SpringServerBootstrap.start(
                ClippyServerApplication.class,
                env.get(ServerEnvs.LOGGING_FILE_NAME),
                ServerEnvs.springProperties(env),
                "clippyServerLauncher");
    }
}

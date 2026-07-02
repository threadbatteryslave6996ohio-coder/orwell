package dev.clippy.auth;

import dev.clippy.bootstrap.SpringServerBootstrap;
import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.CustomLogger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

@SpringBootApplication
public class ClippyAuthServerApplication {
    /**
     * Boots the auth server from an already-resolved environment. The core never reads {@code .env}
     * files or system env itself: whoever launches it (see {@link ClippyAuthServerLauncher}, the
     * combined server, or tests) decides how to fetch the values and passes them in here.
     */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        Env env = AuthServerEnvs.from(environment);
        return SpringServerBootstrap.start(
                ClippyAuthServerApplication.class,
                env.get(AuthServerEnvs.AUTH_LOGGING_FILE_NAME),
                () -> logLocalDatabaseIfApplicable(env),
                AuthServerEnvs.springProperties(env),
                "clippyAuthServerLauncher");
    }

    private static void logLocalDatabaseIfApplicable(Env env) {
        String datasourceUrl = env.get(AuthServerEnvs.AUTH_DATASOURCE_URL);
        if (!isLocalDatabaseUrl(datasourceUrl)) {
            return;
        }

        CustomLogger logger = new CustomLogger("auth-server");
        logger.log("Using local database: " + datasourceUrl);
    }

    private static boolean isLocalDatabaseUrl(String datasourceUrl) {
        String normalized = datasourceUrl == null ? "" : datasourceUrl.trim().toLowerCase();
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("0.0.0.0")
                || normalized.contains("::1")
                || normalized.contains("jdbc:postgresql://auth-postgres")
                || normalized.contains("jdbc:postgresql://postgres");
    }
}

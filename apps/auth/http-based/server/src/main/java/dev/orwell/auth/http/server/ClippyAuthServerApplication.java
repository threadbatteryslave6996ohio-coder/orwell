package dev.orwell.auth.http.server;

import dev.orwell.auth.http.server.config.AuthServerEnvs;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.env.Env;
import dev.orwell.logging.CustomLogger;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClippyAuthServerApplication {
    /**
     * Server descriptor: how the environment is fetched stays with whoever calls
     * {@code SERVER.start(...)} / {@code runOrExit}; the core never reads {@code .env} files itself.
     */
    public static final AppServer SERVER = new AppServer(
            ClippyAuthServerApplication.class,
            "auth-server",
            AuthServerEnvs.ENV,
            ClippyAuthServerApplication::logLocalDatabaseIfApplicable);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
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

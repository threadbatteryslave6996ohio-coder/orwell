package dev.orwell.auth.http.server;

import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Announces at startup that the server is pointed at a local database, so a developer does not
 * mistake a laptop Postgres for the real one.
 *
 * <p>This runs inside the application context rather than before it, so it writes through the
 * shared {@link Logger} bean and lands in the same sink as every other line the server emits.
 */
@Component
public class LocalDatabaseNotice implements ApplicationRunner {
    private final Logger logger;
    private final String datasourceUrl;

    public LocalDatabaseNotice(Logger logger, @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.logger = logger;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isLocalDatabaseUrl(datasourceUrl)) {
            return;
        }
        logger.info("Using local database.", Map.of("datasourceUrl", datasourceUrl));
    }

    static boolean isLocalDatabaseUrl(String datasourceUrl) {
        String normalized = datasourceUrl == null ? "" : datasourceUrl.trim().toLowerCase();
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("0.0.0.0")
                || normalized.contains("::1")
                || normalized.contains("jdbc:postgresql://auth-postgres")
                || normalized.contains("jdbc:postgresql://postgres");
    }
}

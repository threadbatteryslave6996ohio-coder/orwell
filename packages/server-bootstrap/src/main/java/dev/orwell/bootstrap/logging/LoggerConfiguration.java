package dev.orwell.bootstrap.logging;

import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.logging.CompositeLogger;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.FailSafeLogger;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LokiLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.net.URI;

/**
 * Provides the app-wide {@link Logger} bean so individual apps no longer declare their own.
 * The logger name comes from {@code orwell.app.name}, which {@link AppServer} publishes from
 * the descriptor's {@code name(...)}. The bean is exposed as the {@link Logger} interface so the
 * sink stays swappable; apps can override by declaring their own {@link Logger} bean.
 *
 * <p>The default fans out to two sinks with deliberately different audiences. {@link ConsoleLogger}
 * writes human-readable text to stdout/stderr for quick debugging and {@code docker logs}.
 * {@link LokiLogger} pushes structured entries straight to Loki — asynchronously, from a bounded
 * queue, so a slow or unreachable Loki can never delay a request path.
 *
 * <p>The whole thing is wrapped in a {@link FailSafeLogger} because controllers log unguarded in
 * request paths: a sink failure must never become the reason a login returns HTTP 500.
 */
@AutoConfiguration
public class LoggerConfiguration {

    @Bean
    @ConditionalOnMissingBean(Logger.class)
    Logger logger(
            @Value("${orwell.app.name:app}") String appName,
            @Value("${orwell.loki.url:}") String lokiUrl,
            @Value("${orwell.loki.tenant-id:}") String lokiTenantId
    ) {
        ConsoleLogger console = new ConsoleLogger(appName);
        if (lokiUrl == null || lokiUrl.isBlank()) {
            // Console-only is a legitimate local/dev setup, but it is also what a misconfigured
            // deployment looks like — so say so rather than shipping nothing in silence.
            console.warn("LOKI_URL is not set; app logs stay on the console and are not shipped.",
                    java.util.Map.of("app", appName));
            return new FailSafeLogger(console);
        }
        return new FailSafeLogger(new CompositeLogger(
                console,
                new LokiLogger(appName, URI.create(lokiUrl), lokiTenantId)));
    }
}

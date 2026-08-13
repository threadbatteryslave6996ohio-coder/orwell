package dev.orwell.bootstrap.logging;

import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.FailSafeLogger;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LoggerMode;
import dev.orwell.logging.LoggerSetup;
import dev.orwell.logging.LokiLogger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Provides the app-wide {@link Logger} bean so individual apps no longer declare their own.
 * The logger name comes from {@code orwell.app.name}, which {@link AppServer} publishes from
 * the descriptor's {@code name(...)}. The bean is exposed as the {@link Logger} interface so the
 * sink stays swappable; apps can override by declaring their own {@link Logger} bean.
 *
 * <p>Which sinks it fans out to is the {@code LOGGER} environment variable's choice — see
 * {@link LoggerMode} for the values and {@link LoggerSetup} for how they are assembled. The
 * {@link ConsoleLogger} is in every one of them, because stdout is what {@code docker logs} reads.
 * Leaving {@code LOGGER} unset keeps the behavior servers had before it existed: Loki when
 * {@code LOKI_URL} is set, console only when it is not.
 *
 * <p>The whole thing is wrapped in a {@link FailSafeLogger} because controllers log unguarded in
 * request paths: a sink failure must never become the reason a login returns HTTP 500.
 *
 * <p>{@code destroyMethod} is what finally makes {@link LokiLogger#close()} reachable: the sink is
 * buried inside the composite, so before this bean returned a closeable wrapper nothing could
 * flush its queue on shutdown.
 */
@AutoConfiguration
public class LoggerConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Logger.class)
    Logger logger(
            @Value("${" + AppServerEnv.LOGGER_MODE_PROPERTY + ":}") String mode,
            @Value("${orwell.app.name:app}") String appName,
            @Value("${orwell.loki.url:}") String lokiUrl,
            @Value("${orwell.loki.tenant-id:}") String lokiTenantId
    ) {
        return LoggerSetup.fromConfiguration(appName, mode, lokiUrl, lokiTenantId);
    }
}

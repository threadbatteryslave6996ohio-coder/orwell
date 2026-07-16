package dev.orwell.bootstrap.logging;

import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.logging.CustomLogger;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Provides the app-wide {@link CustomLogger} bean so individual apps no longer declare their own.
 * The logger name comes from {@code orwell.app.name}, which {@link AppServer} publishes from
 * the descriptor's {@code name(...)}. Returns the concrete type so both {@link Logger} and
 * {@link CustomLogger} injection points resolve; apps can still override by declaring their own bean.
 */
@AutoConfiguration
public class LoggerConfiguration {

    @Bean
    @ConditionalOnMissingBean(Logger.class)
    CustomLogger logger(@Value("${orwell.app.name:app}") String appName) {
        return new CustomLogger(appName);
    }
}

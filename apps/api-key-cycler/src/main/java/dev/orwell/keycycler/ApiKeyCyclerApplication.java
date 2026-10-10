package dev.orwell.keycycler;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * A reverse proxy that holds several API keys for one upstream and spreads requests across them,
 * so an app behind it sees the pool's combined quota instead of one key's. When the upstream
 * refuses a key, that key is rested and the request goes again with the next one.
 *
 * <p>It is a **standalone** app and shares no code with {@code apps/reverse-proxy}. The two solve
 * different problems — that one records and refuses requests, this one rewrites them — and each
 * carries the forwarding it needs, so neither constrains the other's shape. There is no policy
 * chain and no audit store here: this proxy admits everything, and pointing
 * {@code CYCLER_UPSTREAM_URL} at the reverse proxy is how a rotated request gets recorded.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class ApiKeyCyclerApplication {
    public static final AppServer SERVER =
            new AppServer(ApiKeyCyclerApplication.class, "api-key-cycler", ApiKeyCyclerEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    @Bean
    ApiKeyPool apiKeyPool(
            @Value("${cycler.api-keys}") String keys,
            @Value("${cycler.cooldown-seconds:60}") int cooldownSeconds
    ) {
        return ApiKeyPool.fromConfiguration(keys, Duration.ofSeconds(Math.max(0, cooldownSeconds)));
    }

    @Bean
    ApiKeyFilter apiKeyFilter(
            ApiKeyPool pool,
            @Value("${cycler.key-header:Authorization}") String header,
            @Value("${cycler.key-scheme:Bearer}") String scheme,
            @Value("${cycler.rotate-on-statuses:429,401,403}") String rotateOn,
            Logger logger
    ) {
        Set<Integer> statuses = ApiKeyFilter.statuses(rotateOn);
        logger.info("API key pool loaded.", Map.of(
                // The count, never the keys. See ApiKeyFilter's note on what may be logged.
                "keys", pool.size(),
                "header", header,
                "rotateOn", statuses));
        return new ApiKeyFilter(pool, header, scheme, statuses, logger);
    }

    @Bean
    UpstreamClient upstreamClient(
            @Value("${cycler.upstream-url}") String upstreamUrl,
            @Value("${cycler.upstream-timeout-seconds:30}") int timeoutSeconds
    ) {
        return new UpstreamClient(upstreamUrl, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * The attempt budget, capped at the number of keys. Above that a retry can only reach a key
     * that has already refused this request — the pool skips resting keys, and once every key is
     * resting it hands back the one that recovers soonest.
     */
    @Bean
    ProxyEndpoint proxyEndpoint(
            ApiKeyFilter keys,
            UpstreamClient upstream,
            ApiKeyPool pool,
            Logger logger,
            @Value("${cycler.max-attempts:3}") int maxAttempts
    ) {
        int attempts = Math.max(1, Math.min(maxAttempts, pool.size()));
        if (attempts < maxAttempts) {
            logger.warn("CYCLER_MAX_ATTEMPTS is higher than the number of keys; capping it.", Map.of(
                    "configured", maxAttempts,
                    "keys", pool.size(),
                    "maxAttempts", attempts));
        }
        return new ProxyEndpoint(keys, upstream, logger, attempts);
    }

    /**
     * {@code /health} reports the pool as well as the upstream, so "are we out of keys?" is a
     * question the health check answers rather than one that has to be inferred from a rate of
     * 429s. Positions and counts only — never a key.
     */
    @Bean
    HealthDetailsProvider cyclerHealthDetailsProvider(ProxyEndpoint endpoint) {
        return endpoint::healthDetails;
    }
}

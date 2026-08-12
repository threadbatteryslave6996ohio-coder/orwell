package dev.orwell.reverseproxy;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reverse proxy. Everything it receives goes to one upstream, but only after every {@link Policy}
 * bean has agreed to it; a refused request gets a {@code 403} and never leaves the process.
 * {@link PatternPolicy}, configured from {@code PROXY_BLOCKED_PATTERNS}, is the policy shipped
 * by default — a second one is a new {@code Policy} bean, nothing else.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class ReverseProxyApplication {
    public static final AppServer SERVER =
            new AppServer(ReverseProxyApplication.class, "reverse-proxy", ReverseProxyEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    @Bean
    PatternPolicy patternPolicy(@Value("${proxy.blocked-patterns:}") String blockedPatterns) {
        return PatternPolicy.fromConfiguration(blockedPatterns);
    }

    @Bean
    PolicyChain policyChain(List<Policy> policies) {
        return new PolicyChain(policies);
    }

    @Bean
    UpstreamClient upstreamClient(
            @Value("${proxy.upstream-url}") String upstreamUrl,
            @Value("${proxy.upstream-timeout-seconds:30}") int timeoutSeconds
    ) {
        return new UpstreamClient(upstreamUrl, Duration.ofSeconds(timeoutSeconds));
    }

    @Bean
    ProxyEndpoint proxyEndpoint(PolicyChain policies, UpstreamClient upstream, Logger logger) {
        logger.info("Reverse proxy policies loaded.", Map.of(
                "upstream", upstream.upstreamUrl(),
                "policies", policies.names()));
        return new ProxyEndpoint(policies, upstream, logger);
    }

    @Bean
    HealthDetailsProvider proxyHealthDetailsProvider(ProxyEndpoint endpoint) {
        return endpoint::healthDetails;
    }
}

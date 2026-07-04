package dev.orwell.keeboarder.server;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeeboarderServerConfiguration {
    @Bean
    @ConditionalOnMissingBean(AuthenticationStrategy.class)
    AuthenticationStrategy keeboarderHttpAuthenticationStrategy(
            @Value("${clippy.auth.base-url:http://localhost:8081}") String authBaseUrl
    ) {
        return new HttpAuthenticationStrategy(authBaseUrl);
    }

    @Bean
    KeeboarderWebSocketServer keeboarderWebSocketServer(
            AuthenticationStrategy authenticator,
            @Value("${keeboarder.websocket.enabled:true}") boolean enabled,
            @Value("${keeboarder.websocket.host:0.0.0.0}") String host,
            @Value("${keeboarder.websocket.port:8025}") int port,
            @Value("${keeboarder.websocket.context-path:/ws}") String contextPath,
            @Value("${keeboarder.redis.host:localhost}") String redisHost,
            @Value("${keeboarder.redis.port:6379}") int redisPort
    ) {
        return new KeeboarderWebSocketServer(
                enabled, host, port, contextPath, redisHost, redisPort, authenticator);
    }
}

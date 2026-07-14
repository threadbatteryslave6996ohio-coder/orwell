package dev.orwell.keeboarder.server.config;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.keeboarder.server.websocket.ChatEndpoint;
import dev.orwell.keeboarder.server.websocket.KeeboarderWebSocketRuntime;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * Hosts the Keeboarder WebSocket endpoint ({@link ChatEndpoint}) on Spring Boot's embedded
 * servlet container and wires the shared Redis cache / authenticator into it. All of it is
 * gated on {@code keeboarder.websocket.enabled} (default on): when disabled, no WebSocket
 * endpoint is registered at all, matching the previous standalone-listener behavior.
 */
@Configuration
public class KeeboarderServerConfiguration {

    /** Enables scanning and registration of {@code ServerEndpointConfig} beans on the embedded container. */
    @Bean
    @ConditionalOnBooleanProperty(name = "keeboarder.websocket.enabled", matchIfMissing = true)
    ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    /**
     * Registers the chat endpoint at {@code <context-path>/chat} (default {@code /ws/chat}), preserving
     * the path clients used against the previous standalone server. The container instantiates the
     * endpoint per connection; {@link ChatEndpoint} shares state statically.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "keeboarder.websocket.enabled", matchIfMissing = true)
    ServerEndpointConfig chatEndpointConfig(
            @Value("${keeboarder.websocket.context-path:/ws}") String contextPath
    ) {
        return ServerEndpointConfig.Builder.create(ChatEndpoint.class, chatPath(contextPath)).build();
    }

    private static String chatPath(String contextPath) {
        String base = contextPath == null ? "" : contextPath.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.isEmpty() && !base.startsWith("/")) {
            base = "/" + base;
        }
        return base + "/chat";
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    KeeboarderWebSocketRuntime keeboarderWebSocketRuntime(
            AuthenticationStrategy authenticator,
            @Value("${keeboarder.websocket.enabled:true}") boolean enabled,
            @Value("${keeboarder.redis.host:localhost}") String redisHost,
            @Value("${keeboarder.redis.port:6379}") int redisPort
    ) {
        return new KeeboarderWebSocketRuntime(enabled, redisHost, redisPort, authenticator);
    }
}

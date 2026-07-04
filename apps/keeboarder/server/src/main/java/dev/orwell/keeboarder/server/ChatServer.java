package dev.orwell.keeboarder.server;

import org.springframework.boot.SpringApplication;

import java.util.Map;

public class ChatServer {
    public static void main(String[] args) {
        Map<String, Object> defaults = Map.ofEntries(
                Map.entry("server.address", env("HTTP_HOST", "0.0.0.0")),
                Map.entry("server.port", env("HTTP_PORT", "8080")),
                Map.entry("keeboarder.server.route-prefix", "/api"),
                Map.entry("keeboarder.websocket.host", env("WEBSOCKET_HOST", "localhost")),
                Map.entry("keeboarder.websocket.port", env("WEBSOCKET_PORT", "8025")),
                Map.entry("keeboarder.redis.host", env("REDIS_HOST", "localhost")),
                Map.entry("keeboarder.redis.port", env("REDIS_PORT", "6379")),
                Map.entry("clippy.auth.base-url", env("CLIPPY_AUTH_BASE_URL", "http://localhost:8081"))
        );
        SpringApplication application = new SpringApplication(KeeboarderServerApplication.class);
        application.setDefaultProperties(defaults);
        application.run(args);
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }
}

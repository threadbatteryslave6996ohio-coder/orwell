package dev.orwell.keeboarder.mac;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public record ClientConfig(String serverUrl, String authBaseUrl, String name, String clientId, String clientSecret) {
    public static ClientConfig fromArgs(String[] args) throws Exception {
        String serverUrl = System.getenv().getOrDefault("KEEBOARDER_SERVER_URL", "ws://localhost:8025/ws/chat");
        String authBaseUrl = System.getenv().getOrDefault("KEEBOARDER_AUTH_BASE_URL", "http://localhost:8081");
        String name = System.getenv().getOrDefault("KEEBOARDER_CLIENT_NAME", defaultName());
        String clientId = System.getenv().getOrDefault("KEEBOARDER_CLIENT_ID", "");
        String clientSecret = System.getenv().getOrDefault("KEEBOARDER_CLIENT_SECRET", "");

        List<String> tokens = new ArrayList<>(List.of(args));
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            switch (token) {
                case "--server-url" -> serverUrl = requireValue(tokens, ++i, "--server-url");
                case "--auth-base-url" -> authBaseUrl = requireValue(tokens, ++i, "--auth-base-url");
                case "--name" -> name = requireValue(tokens, ++i, "--name");
                case "--client-id" -> clientId = requireValue(tokens, ++i, "--client-id");
                case "--client-secret" -> clientSecret = requireValue(tokens, ++i, "--client-secret");
                default -> {
                    if (!token.isBlank()) {
                        throw new IllegalArgumentException("Unknown argument: " + token);
                    }
                }
            }
        }

        if (clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required. Set --client-id or KEEBOARDER_CLIENT_ID.");
        }
        if (clientSecret.isBlank()) {
            throw new IllegalArgumentException("clientSecret is required. Set --client-secret or KEEBOARDER_CLIENT_SECRET.");
        }

        return new ClientConfig(serverUrl, authBaseUrl, name, clientId, clientSecret);
    }

    private static String requireValue(List<String> tokens, int index, String flag) {
        if (index >= tokens.size()) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return tokens.get(index);
    }

    private static String defaultName() throws Exception {
        String host = InetAddress.getLocalHost().getHostName();
        if (host == null || host.isBlank()) {
            return "MacClient";
        }
        return "Mac-" + host;
    }
}

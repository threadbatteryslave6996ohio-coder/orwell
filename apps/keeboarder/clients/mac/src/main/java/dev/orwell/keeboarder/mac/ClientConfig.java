package dev.orwell.keeboarder.mac;

import java.util.Map;

public record ClientConfig(String serverUrl, String authBaseUrl, String name, String clientId, String clientSecret) {
    public static ClientConfig fromEnv(Map<String, String> rawEnv) {
        var env = ClientEnvs.from(rawEnv);
        String serverUrl = env.get(ClientEnvs.KEEBOARDER_SERVER_URL);
        String authBaseUrl = env.get(ClientEnvs.KEEBOARDER_AUTH_BASE_URL);
        String name = env.get(ClientEnvs.KEEBOARDER_CLIENT_NAME);
        if (name.isBlank()) {
            name = ClientEnvs.defaultName();
        }
        String clientId = env.get(ClientEnvs.KEEBOARDER_CLIENT_ID);
        String clientSecret = env.get(ClientEnvs.KEEBOARDER_CLIENT_SECRET);
        return new ClientConfig(serverUrl, authBaseUrl, name, clientId, clientSecret);
    }
}

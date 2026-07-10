package dev.orwell.env.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.env.Env;
import dev.orwell.env.EnvFiles;
import dev.orwell.secrets.client.SecretsManagerClient;
import dev.orwell.secrets.client.SecretsManagerEnvConfig;
import dev.orwell.secrets.client.TokenAuthProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class EnvLoader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private EnvLoader() {
    }

    public static Map<String, String> load(String source) {
        return switch (source) {
            case "file" -> loadFromFile();
            case "url" -> loadFromSecretsManager();
            default -> throw new IllegalArgumentException(
                    "Unknown source: " + source + " (expected 'file' or 'url')");
        };
    }

    private static Map<String, String> loadFromFile() {
        try {
            return EnvFiles.load();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load .env file", e);
        }
    }

    private static Map<String, String> loadFromSecretsManager() {
        System.out.println("[EnvLoader] Loading environment from secrets manager via URL");
        Map<String, String> raw;
        try {
            raw = EnvFiles.load();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load .env file for secrets manager configuration", e);
        }

        Env config = SecretsManagerEnvConfig.SCHEMA.from(raw);
        var auth = new TokenAuthProvider(
                config.get(SecretsManagerEnvConfig.TOKEN),
                config.get(SecretsManagerEnvConfig.CLIENT_ID));

        try (var client = new SecretsManagerClient(
                config.get(SecretsManagerEnvConfig.URL), auth)) {
            var bundle = client.getBundle(config.get(SecretsManagerEnvConfig.BUNDLE_ID));
            Map<String, String> entries = new HashMap<>();
            for (var entry : bundle.environments()) {
                entries.put(entry.name(), entry.value());
            }
            System.out.println("[EnvLoader] Loaded " + entries.size()
                    + " values from secrets manager bundle '" + bundle.name() + "'");
            return entries;
        }
    }

    public static Map<String, String> fetchRemote(String url) throws IOException {
        System.out.println("[EnvLoader] Loading environment from remote URL: " + url);
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Couldn't reach env url: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Remote env returned HTTP " + response.statusCode()
                    + " for " + url);
        }
        return JSON.readValue(response.body(), new TypeReference<Map<String, String>>() {});
    }
}

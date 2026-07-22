package dev.orwell.secrets.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.secrets.client.dto.Bundle;
import dev.orwell.secrets.client.dto.BundleDetail;
import dev.orwell.secrets.client.dto.Environment;
import dev.orwell.secrets.client.dto.Group;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class SecretsManagerClient implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final SecretsAuthProvider auth;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SecretsManagerClient(String baseUrl, SecretsAuthProvider auth) {
        this.baseUrl = normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.auth = Objects.requireNonNull(auth, "auth");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public SecretsManagerClient(String baseUrl, SecretsAuthProvider auth, HttpClient httpClient) {
        this.baseUrl = normalizeBaseUrl(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.auth = Objects.requireNonNull(auth, "auth");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = new ObjectMapper();
    }

    public List<Group> listGroups() {
        return get("/groups", new TypeReference<List<Group>>() {});
    }

    public List<Environment> listEnvironments(long groupId) {
        return get("/groups/" + groupId + "/envs", new TypeReference<List<Environment>>() {});
    }

    public Environment getEnvironmentByGroupAndName(long groupId, String name) {
        return get("/groups/" + groupId + "/envs/by-name/" + name, Environment.class);
    }

    public Environment getEnvironmentById(long groupId, long envId) {
        return get("/groups/" + groupId + "/envs/" + envId, Environment.class);
    }

    public List<Bundle> listBundles() {
        return get("/bundles", new TypeReference<List<Bundle>>() {});
    }

    public BundleDetail getBundle(long id) {
        return get("/bundles/" + id, BundleDetail.class);
    }

    @Override
    public void close() {
    }

    private <T> T get(String path, Class<T> responseType) {
        return execute(buildRequest(path).GET().build(), (mapper, body) -> mapper.readValue(body, responseType));
    }

    private <T> T get(String path, TypeReference<T> typeRef) {
        return execute(buildRequest(path).GET().build(), (mapper, body) -> mapper.readValue(body, typeRef));
    }

    private HttpRequest.Builder buildRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", auth.getAuthorizationHeader())
                .header("X-Client-Id", auth.getClientId())
                .timeout(TIMEOUT);
    }

    private <T> T execute(HttpRequest request, ResponseReader<T> reader) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SecretsManagerException(
                        "Secrets manager returned " + response.statusCode() + ": " + response.body(),
                        response.statusCode());
            }
            return reader.read(objectMapper, response.body());
        } catch (IOException e) {
            throw new SecretsManagerException("Failed to parse response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsManagerException("Request interrupted", e);
        }
    }

    /** Defers the choice of {@code Class} vs {@code TypeReference} deserialization to the caller
     *  while keeping the send + status handling in one place. */
    @FunctionalInterface
    private interface ResponseReader<T> {
        T read(ObjectMapper mapper, String body) throws IOException;
    }

    private static String normalizeBaseUrl(String url) {
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}

package dev.orwell.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.secrets.model.AdminIdentity;
import dev.orwell.secrets.model.AccessorIdentity;
import dev.orwell.secrets.repository.AdminIdentityRepository;
import dev.orwell.secrets.repository.AccessorIdentityRepository;
import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecretsManagerIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void secretsProperties(DynamicPropertyRegistry registry) {
        registry.add("secrets.auth.base-url", () -> "http://localhost:1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AdminIdentityRepository adminRepo;

    @Autowired
    private AccessorIdentityRepository accessorRepo;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ADMIN_CLIENT = "admin-user";
    private static final String ACCESSOR_CLIENT = "accessor-user";
    private static final String UNAUTHORIZED_CLIENT = "unknown-user";
    private static final String VALID_TOKEN = "valid-token";

    @BeforeEach
    void setUp() {
        adminRepo.deleteAll();
        accessorRepo.deleteAll();
        adminRepo.save(new AdminIdentity(ADMIN_CLIENT, Instant.now()));
        accessorRepo.save(new AccessorIdentity(ACCESSOR_CLIENT, Instant.now()));
    }

    @Test
    void fullWorkflowAdminAndAccessor() throws Exception {
        String groupId = adminCreatesGroup();
        Long env1Id = adminCreatesEnvironment(groupId, "DB_URL", "jdbc:postgresql://localhost:5432/db");
        Long env2Id = adminCreatesEnvironment(groupId, "API_KEY", "sk-abc123");
        adminCreatesEnvironment(groupId, "OTHER_SECRET", "unused");
        String bundleId = adminCreatesBundle(groupId, env1Id, env2Id);

        accessorListsGroups();
        accessorGetsAllEnvsInGroup(groupId, 3);
        accessorGetsEnvByName(groupId, "API_KEY", "sk-abc123");
        accessorGetsBundle(bundleId, List.of("DB_URL", "API_KEY"));

        unauthorizedUserIsRejected();
        unauthorizedUserCannotCreateGroup();
        accessorCannotCreateGroup();
    }

    private String adminCreatesGroup() throws Exception {
        String json = """
                {"name": "production", "description": "Production secrets"}
                """;
        HttpResponse<String> response = sendAdminPost("/admin/groups", json);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode node = objectMapper.readTree(response.body());
        assertThat(node.get("name").asText()).isEqualTo("production");
        assertThat(node.get("createdBy").asText()).isEqualTo(ADMIN_CLIENT);
        return node.get("id").asText();
    }

    private Long adminCreatesEnvironment(String groupId, String name, String value) throws Exception {
        String json = """
                {"name": "%s", "value": "%s"}
                """.formatted(name, value);
        HttpResponse<String> response = sendAdminPost("/admin/groups/%s/envs".formatted(groupId), json);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode node = objectMapper.readTree(response.body());
        assertThat(node.get("name").asText()).isEqualTo(name);
        assertThat(node.get("value").asText()).isEqualTo(value);
        return node.get("id").asLong();
    }

    private String adminCreatesBundle(String groupId, Long env1Id, Long env2Id) throws Exception {
        String json = """
                {"name": "production-bundle", "description": "Production env bundle", "envIds": [%d, %d]}
                """.formatted(env1Id, env2Id);
        HttpResponse<String> response = sendAdminPost("/admin/bundles", json);
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode node = objectMapper.readTree(response.body());
        assertThat(node.get("name").asText()).isEqualTo("production-bundle");
        return node.get("id").asText();
    }

    private void accessorListsGroups() throws Exception {
        HttpResponse<String> response = sendAccessorGet("/groups");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("name").asText()).isEqualTo("production");
    }

    private void accessorGetsAllEnvsInGroup(String groupId, int expectedCount) throws Exception {
        HttpResponse<String> response = sendAccessorGet("/groups/%s/envs".formatted(groupId));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(expectedCount);
    }

    private void accessorGetsEnvByName(String groupId, String name, String expectedValue) throws Exception {
        HttpResponse<String> response = sendAccessorGet(
                "/groups/%s/envs/by-name/%s".formatted(groupId, name));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode node = objectMapper.readTree(response.body());
        assertThat(node.get("name").asText()).isEqualTo(name);
        assertThat(node.get("value").asText()).isEqualTo(expectedValue);
    }

    private void accessorGetsBundle(String bundleId, List<String> expectedEnvNames) throws Exception {
        HttpResponse<String> response = sendAccessorGet("/bundles/%s".formatted(bundleId));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode node = objectMapper.readTree(response.body());
        assertThat(node.get("name").asText()).isEqualTo("production-bundle");
        assertThat(node.get("environments").isArray()).isTrue();
        assertThat(node.get("environments")).hasSize(expectedEnvNames.size());
        for (int i = 0; i < expectedEnvNames.size(); i++) {
            assertThat(node.get("environments").get(i).get("name").asText())
                    .isEqualTo(expectedEnvNames.get(i));
        }
    }

    private void unauthorizedUserIsRejected() throws Exception {
        HttpResponse<String> response = sendGet("/groups", UNAUTHORIZED_CLIENT, VALID_TOKEN);
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private void unauthorizedUserCannotCreateGroup() throws Exception {
        HttpResponse<String> response = sendPost(
                "/admin/groups", UNAUTHORIZED_CLIENT, VALID_TOKEN, """
                        {"name": "should-fail", "description": ""}
                        """);
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private void accessorCannotCreateGroup() throws Exception {
        HttpResponse<String> response = sendPost(
                "/admin/groups", ACCESSOR_CLIENT, VALID_TOKEN, """
                        {"name": "should-fail", "description": ""}
                        """);
        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> sendAdminPost(String path, String json) throws IOException, InterruptedException {
        return sendPost(path, ADMIN_CLIENT, VALID_TOKEN, json);
    }

    private HttpResponse<String> sendAccessorGet(String path) throws IOException, InterruptedException {
        return sendGet(path, ACCESSOR_CLIENT, VALID_TOKEN);
    }

    private HttpResponse<String> sendPost(String path, String clientId, String token, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("Content-Type", "application/json")
                .header("X-Client-Id", clientId)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendGet(String path, String clientId, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Client-Id", clientId)
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AuthenticationStrategy authenticationStrategy() {
            return (clientId, token) -> "valid-token".equals(token);
        }
    }
}

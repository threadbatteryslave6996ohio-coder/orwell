package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.UserSecretRepository;
import dev.orwell.testing.PostgresIntegrationTest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mailbox-administration API. The poll interval is set far beyond the test run so the
 * scheduled poller never fires: nothing here is about ingestion, and a poller racing against these
 * assertions would only add flakiness.
 *
 * <p>Each test uses its own email and client id, because the schema is created once per context
 * and rows written by one test are still there for the next.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserApiIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void gmailProperties(DynamicPropertyRegistry registry) {
        registry.add("orwell.auth.base-url", () -> "http://localhost:1");
        registry.add("gmail.auth.client-id", () -> "gmail-general");
        registry.add("gmail.auth.client-secret", () -> "");
        registry.add("gmail.webhook-clients", () -> "");
        registry.add("gmail.route-prefix", () -> "");
        registry.add("gmail.poll-interval-seconds", () -> 3600);
        registry.add("gmail.poll-concurrency", () -> 4);
        registry.add("gmail.delivery-interval-seconds", () -> 3600);
        registry.add("gmail.imap.host", () -> "127.0.0.1");
        registry.add("gmail.imap.port", () -> 1);
        registry.add("gmail.imap.ssl", () -> false);
        registry.add("gmail.imap.folder", () -> "INBOX");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserSecretRepository secrets;

    @Autowired
    private UserRepository users;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsAMailboxAndReturnsItWithoutASecret() throws Exception {
        HttpResponse<String> response = createUser("create@example.com", "create-client");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("email").asText()).isEqualTo("create@example.com");
        assertThat(body.get("clientId").asText()).isEqualTo("create-client");
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.has("imapPassword")).isFalse();
    }

    @Test
    void rejectsADuplicateEmail() throws Exception {
        assertThat(createUser("dupe-email@example.com", "dupe-email-a").statusCode()).isEqualTo(201);

        assertThat(createUser("dupe-email@example.com", "dupe-email-b").statusCode()).isEqualTo(409);
    }

    @Test
    void rejectsADuplicateClientId() throws Exception {
        assertThat(createUser("dupe-client-a@example.com", "dupe-client").statusCode()).isEqualTo(201);

        assertThat(createUser("dupe-client-b@example.com", "dupe-client").statusCode()).isEqualTo(409);
    }

    @Test
    void rejectsAMalformedEmail() throws Exception {
        assertThat(createUser("not-an-email", "malformed-client").statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsABlankClientId() throws Exception {
        assertThat(createUser("blank-client@example.com", "  ").statusCode()).isEqualTo(400);
    }

    @Test
    void storesAndThenReplacesTheSecretWithoutEverCreatingASecondRow() throws Exception {
        long id = createdUserId("secret@example.com", "secret-client");

        assertThat(setSecret(id, "first-password").statusCode()).isEqualTo(204);
        assertThat(setSecret(id, "second-password").statusCode()).isEqualTo(204);

        // One-to-one is the whole point of the secrets table: a replace must update, not accumulate.
        assertThat(secrets.findAll().stream().filter(s -> s.getUser().getId().equals(id)).count())
                .isEqualTo(1);
        assertThat(secrets.findByUserId(id)).isPresent()
                .get().extracting(s -> s.getImapPassword()).isEqualTo("second-password");
    }

    @Test
    void returnsNotFoundWhenSettingASecretForAnUnknownUser() throws Exception {
        assertThat(setSecret(999_999L, "orphan-password").statusCode()).isEqualTo(404);
    }

    @Test
    void rejectsABlankSecret() throws Exception {
        long id = createdUserId("blank-secret@example.com", "blank-secret-client");

        assertThat(setSecret(id, "   ").statusCode()).isEqualTo(400);
        assertThat(secrets.findByUserId(id)).isEmpty();
    }

    @Test
    void listsMailboxesWithoutEverExposingASecret() throws Exception {
        long id = createdUserId("listed@example.com", "listed-client");
        assertThat(setSecret(id, "listed-password").statusCode()).isEqualTo(204);

        HttpResponse<String> response = send("GET", "/users", "listed-client", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("listed-password").doesNotContain("imapPassword");
        assertThat(objectMapper.readTree(response.body()).isArray()).isTrue();
    }

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/users")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"anon@example.com\",\"clientId\":\"anon-client\"}"))
                .build();

        assertThat(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
        assertThat(users.findByClientId("anon-client")).isEmpty();
    }

    private long createdUserId(String email, String clientId) throws Exception {
        HttpResponse<String> response = createUser(email, clientId);
        assertThat(response.statusCode()).isEqualTo(201);
        return objectMapper.readTree(response.body()).get("id").asLong();
    }

    private HttpResponse<String> createUser(String email, String clientId) throws Exception {
        return send("POST", "/users", "admin-client",
                "{\"email\":\"%s\",\"clientId\":\"%s\"}".formatted(email, clientId));
    }

    private HttpResponse<String> setSecret(long id, String password) throws Exception {
        return send("PUT", "/users/" + id + "/secret", "admin-client",
                "{\"imapPassword\":\"%s\"}".formatted(password));
    }

    private String url(String path) {
        return "http://localhost:%d%s".formatted(port, path);
    }

    private HttpResponse<String> send(String method, String path, String clientId, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url(path)))
                .header("X-Client-Id", clientId)
                .header("Authorization", "Bearer valid-token")
                .header("Content-Type", "application/json")
                .method(method, payload).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

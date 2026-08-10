package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging and limit behaviour of the read API, seeded straight through the repositories. The poller
 * is parked on a one-hour interval: these assertions are about what the controller returns for a
 * known set of rows, and live ingestion would only make the counts move underneath them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailApiIntegrationTest extends PostgresIntegrationTest {
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @DynamicPropertySource
    static void gmailProperties(DynamicPropertyRegistry registry) {
        registry.add("orwell.auth.base-url", () -> "http://localhost:1");
        registry.add("gmail.auth.client-id", () -> "gmail-general");
        registry.add("gmail.auth.client-secret", () -> "");
        registry.add("gmail.webhook-clients", () -> "");
        registry.add("gmail.route-prefix", () -> "");
        registry.add("gmail.poll-interval-seconds", () -> 3600);
        registry.add("gmail.poll-concurrency", () -> 4);
        registry.add("gmail.max-message-bytes", () -> 26_214_400L);
        registry.add("gmail.public-base-url", () -> "");
        registry.add("gmail.delivery-interval-seconds", () -> 3600);
        registry.add("gmail.imap.host", () -> "127.0.0.1");
        registry.add("gmail.imap.port", () -> 1);
        registry.add("gmail.imap.ssl", () -> false);
        registry.add("gmail.imap.folder", () -> "INBOX");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @Autowired
    private EmailMessageRepository mails;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserEntity owner;
    private String clientId;

    @BeforeEach
    void createOwner() {
        int n = UNIQUE.incrementAndGet();
        clientId = "reader-" + n;
        owner = users.save(new UserEntity("reader-" + n + "@example.com", clientId, Instant.now()));
    }

    @Test
    void defaultsToFiftyMessages() throws Exception {
        seed(60);

        assertThat(arrayLength(httpGet("/mails"))).isEqualTo(50);
    }

    @Test
    void honoursAnExplicitSmallerLimit() throws Exception {
        seed(20);

        assertThat(arrayLength(httpGet("/mails?limit=5"))).isEqualTo(5);
    }

    @Test
    void capsTheLimitAtFiveHundred() throws Exception {
        seed(505);

        assertThat(arrayLength(httpGet("/mails?limit=100000"))).isEqualTo(500);
    }

    @Test
    void treatsANonPositiveLimitAsTheDefault() throws Exception {
        seed(60);

        assertThat(arrayLength(httpGet("/mails?limit=0"))).isEqualTo(50);
        assertThat(arrayLength(httpGet("/mails?limit=-7"))).isEqualTo(50);
    }

    @Test
    void listsNewestFirst() throws Exception {
        List<Long> ids = seed(3);

        JsonNode body = objectMapper.readTree(httpGet("/mails").body());

        assertThat(idsOf(body)).containsExactly(ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    void returnsNoContentWhenTheMailboxIsEmpty() throws Exception {
        assertThat(httpGet("/mails/latest").statusCode()).isEqualTo(204);
    }

    @Test
    void checkpointReturnsOnlyNewerMailOldestFirst() throws Exception {
        List<Long> ids = seed(5);

        JsonNode body = objectMapper.readTree(httpGet("/mails/latest?checkpoint=" + ids.get(1)).body());

        assertThat(idsOf(body)).containsExactly(ids.get(2), ids.get(3), ids.get(4));
    }

    @Test
    void checkpointRespectsTheLimit() throws Exception {
        List<Long> ids = seed(60);

        JsonNode body = objectMapper.readTree(
                httpGet("/mails/latest?checkpoint=" + ids.get(0) + "&limit=3").body());

        assertThat(idsOf(body)).containsExactly(ids.get(1), ids.get(2), ids.get(3));
    }

    @Test
    void checkpointBeyondTheNewestReturnsAnEmptyArray() throws Exception {
        List<Long> ids = seed(3);

        JsonNode body = objectMapper.readTree(
                httpGet("/mails/latest?checkpoint=" + ids.get(2)).body());

        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    /** Inserts {@code count} messages for the current owner, returning their ids in insertion order. */
    private List<Long> seed(int count) {
        List<EmailMessageEntity> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(new EmailMessageEntity(
                    owner, "<%s-%d@example.com>".formatted(clientId, i), i,
                    "Subject " + i, "alice@example.com", owner.getEmail(),
                    Instant.now(), "body " + i, "", 0L, false, Instant.now()));
        }
        return mails.saveAll(batch).stream().map(EmailMessageEntity::getId).toList();
    }

    private int arrayLength(HttpResponse<String> response) throws IOException {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        return body.size();
    }

    private static List<Long> idsOf(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asLong()).toList();
    }

    private HttpResponse<String> httpGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Client-Id", clientId)
                .header("Authorization", "Bearer valid-token")
                .GET().build();
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

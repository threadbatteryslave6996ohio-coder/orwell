package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subscription API, and specifically its scoping: which mailbox a subscription attaches to
 * comes from the authenticated client id, so the interesting assertions are the ones about what a
 * second consumer cannot see or delete.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubscriptionApiIntegrationTest extends PostgresIntegrationTest {
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
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private EmailMessageRepository mails;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UserEntity bob;
    private UserEntity carol;
    private String bobClient;
    private String carolClient;

    @BeforeEach
    void createUsers() {
        int n = UNIQUE.incrementAndGet();
        bobClient = "bob-" + n;
        carolClient = "carol-" + n;
        bob = users.save(new UserEntity("bob-" + n + "@example.com", bobClient, Instant.now()));
        carol = users.save(new UserEntity("carol-" + n + "@example.com", carolClient, Instant.now()));
    }

    @Test
    void attachesANewSubscriptionToTheCallersOwnMailbox() throws Exception {
        HttpResponse<String> response = post(bobClient, "https://receiver.example.com/bob");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("account").asText()).isEqualTo(bob.getEmail());
        assertThat(body.get("url").asText()).isEqualTo("https://receiver.example.com/bob");
        assertThat(body.get("active").asBoolean()).isTrue();

        List<WebhookSubscriptionEntity> stored =
                subscriptions.findByUserIdOrderByIdAsc(bob.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getUser().getId()).isEqualTo(bob.getId());
    }

    /** The central property: one consumer's subscriptions are invisible to another. */
    @Test
    void listsOnlyTheCallersOwnSubscriptions() throws Exception {
        post(bobClient, "https://receiver.example.com/bob");
        post(carolClient, "https://receiver.example.com/carol");

        assertThat(urlsOf(get(bobClient)))
                .containsExactly("https://receiver.example.com/bob");
        assertThat(urlsOf(get(carolClient)))
                .containsExactly("https://receiver.example.com/carol");
    }

    /** A guessed id must not be a way to unsubscribe someone else's receiver. */
    @Test
    void refusesToDeleteAnotherUsersSubscription() throws Exception {
        long bobsId = objectMapper.readTree(
                post(bobClient, "https://receiver.example.com/bob").body()).get("id").asLong();

        HttpResponse<String> response = delete(carolClient, bobsId);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(subscriptions.findByUserIdOrderByIdAsc(bob.getId())).hasSize(1);
    }

    @Test
    void deletesTheCallersOwnSubscription() throws Exception {
        long id = objectMapper.readTree(
                post(bobClient, "https://receiver.example.com/bob").body()).get("id").asLong();

        assertThat(delete(bobClient, id).statusCode()).isEqualTo(204);
        assertThat(subscriptions.findByUserIdOrderByIdAsc(bob.getId())).isEmpty();
    }

    @Test
    void rejectsADuplicateUrlForTheSameMailboxButAllowsItForAnother() throws Exception {
        post(bobClient, "https://shared.example.com/mail");

        assertThat(post(bobClient, "https://shared.example.com/mail").statusCode()).isEqualTo(409);
        // Two users pointing at the same receiver is legitimate: each gets its own row.
        assertThat(post(carolClient, "https://shared.example.com/mail").statusCode()).isEqualTo(201);
    }

    @Test
    void rejectsAUrlTheDeliveryPathCouldNotPostTo() throws Exception {
        assertThat(post(bobClient, "not-a-url").statusCode()).isEqualTo(400);
        assertThat(post(bobClient, "ftp://receiver.example.com/mail").statusCode()).isEqualTo(400);
        assertThat(post(bobClient, "/relative/path").statusCode()).isEqualTo(400);
    }

    /**
     * Subscribing must not replay the mailbox's whole history — the same rule registering a
     * mailbox follows. The cursor therefore starts at the current head, not at zero.
     */
    @Test
    void startsTheDeliveryCursorAtTheMailboxHead() throws Exception {
        EmailMessageEntity stored = mails.save(new EmailMessageEntity(
                bob, "<seed@example.com>", 1L, "Already stored", "alice@example.com",
                bob.getEmail(), Instant.now(), "body", "", 0L, false, Instant.now()));

        JsonNode body = objectMapper.readTree(
                post(bobClient, "https://receiver.example.com/bob").body());

        assertThat(body.get("lastDeliveredId").asLong()).isEqualTo(stored.getId());
    }

    /** An empty mailbox has no head to start from, so the cursor begins at zero. */
    @Test
    void startsTheDeliveryCursorAtZeroForAnEmptyMailbox() throws Exception {
        JsonNode body = objectMapper.readTree(
                post(carolClient, "https://receiver.example.com/carol").body());

        assertThat(body.get("lastDeliveredId").asLong()).isZero();
    }

    @Test
    void rejectsAClientThatOwnsNoMailbox() throws Exception {
        assertThat(post("stranger-client", "https://receiver.example.com/x").statusCode())
                .isEqualTo(403);
    }

    private HttpResponse<String> post(String clientId, String url)
            throws IOException, InterruptedException {
        return send(request(clientId, "/subscriptions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.createObjectNode().put("url", url).toString())));
    }

    private HttpResponse<String> get(String clientId) throws IOException, InterruptedException {
        return send(request(clientId, "/subscriptions").GET());
    }

    private HttpResponse<String> delete(String clientId, long id)
            throws IOException, InterruptedException {
        return send(request(clientId, "/subscriptions/" + id).DELETE());
    }

    private HttpRequest.Builder request(String clientId, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Client-Id", clientId)
                .header("Authorization", "Bearer valid-token");
    }

    private HttpResponse<String> send(HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<String> urlsOf(HttpResponse<String> response) throws IOException {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        return StreamSupport.stream(body.spliterator(), false)
                .map(node -> node.get("url").asText()).toList();
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

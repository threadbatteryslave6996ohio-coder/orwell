package dev.orwell.google.gmail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.orwell.google.gmail.GmailTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Storage, and the legacy {@code GMAIL_WEBHOOK_CLIENTS} broadcast — the only fan-out
 * {@link GmailService} still performs. Per-subscription delivery is cursor-driven and lives in
 * {@link WebhookDeliveryJobTest}.
 *
 * <p>Run against a real in-process auth server and real webhook receivers: the code under test
 * speaks HTTP and caches a token across deliveries, so stubbing the transport would leave the
 * interesting parts untested. The repositories are mocked — this is about what leaves the service.
 */
class GmailServiceWebhookTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private HttpServer authServer;
    private HttpServer webhookServer;
    private final AtomicInteger logins = new AtomicInteger();
    private final Map<String, List<String>> received = new ConcurrentHashMap<>();
    private final Map<String, List<String>> authorizations = new ConcurrentHashMap<>();

    private EmailMessageRepository repository;
    private WebhookSubscriptionRepository subscriptions;
    private UserEntity user;

    @BeforeEach
    void startServers() throws IOException {
        repository = mock(EmailMessageRepository.class);
        when(repository.save(any(EmailMessageEntity.class))).thenAnswer(call -> call.getArgument(0));
        subscriptions = mock(WebhookSubscriptionRepository.class);
        when(subscriptions.findByUserIdAndActiveTrueOrderByIdAsc(any())).thenReturn(List.of());
        user = user(1L, "owner@example.com", "owner-client");

        authServer = HttpServer.create(new InetSocketAddress(0), 0);
        authServer.createContext("/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"clientId\":\"gmail-general\",\"token\":\"token-%d\"}"
                    .formatted(logins.incrementAndGet()));
        });
        authServer.start();

        webhookServer = HttpServer.create(new InetSocketAddress(0), 0);
        webhookServer.start();
    }

    @AfterEach
    void stopServers() {
        authServer.stop(0);
        webhookServer.stop(0);
    }

    @Test
    void postsEveryStoredMessageToEveryBroadcastClientWithBearerAuth() throws Exception {
        webhook("/one", exchange -> 200);
        webhook("/two", exchange -> 200);
        GmailService service = service(url("/one") + "," + url("/two"));

        service.deliver(user, message("<a@example.com>", "Hello"), 1L);

        assertThat(received.get("/one")).hasSize(1);
        assertThat(received.get("/one").get(0)).contains("\"subject\":\"Hello\"");
        assertThat(received.get("/two")).hasSize(1);
        assertThat(authorizations.get("/one")).containsExactly("Bearer token-1");
        // One token is cached and reused across clients rather than logging in per delivery.
        assertThat(logins.get()).isEqualTo(1);
        verify(repository).save(any(EmailMessageEntity.class));
    }

    /** The broadcast list is explicitly every mailbox — that is what makes it legacy. */
    @Test
    void deliversEveryMailboxToTheBroadcastList() throws Exception {
        webhook("/all", exchange -> 200);
        UserEntity bob = user(2L, "bob@example.com", "bob-client");
        GmailService service = service(url("/all"));

        service.deliver(user, message("<a@example.com>", "Owner mail"), 1L);
        service.deliver(bob, message("<b@example.com>", "Bob mail", "bob@example.com"), 2L);

        assertThat(received.get("/all")).hasSize(2);
        assertThat(received.get("/all").get(0)).contains("\"account\":\"owner@example.com\"");
        assertThat(received.get("/all").get(1)).contains("\"account\":\"bob@example.com\"");
    }

    /**
     * A URL the user has also subscribed is left to the cursor-tracked path, so migrating a
     * receiver onto a subscription never opens a window where it is delivered twice.
     */
    @Test
    void skipsABroadcastUrlThatTheUserHasAlsoSubscribed() throws Exception {
        webhook("/both", exchange -> 200);
        webhook("/broadcast-only", exchange -> 200);
        when(subscriptions.findByUserIdAndActiveTrueOrderByIdAsc(user.getId())).thenReturn(
                List.of(new WebhookSubscriptionEntity(user, url("/both"), 0L, Instant.now())));
        GmailService service = service(url("/both") + "," + url("/broadcast-only"));

        service.deliver(user, message("<dual@example.com>", "Once"), 1L);

        assertThat(received.getOrDefault("/both", List.of())).isEmpty();
        assertThat(received.get("/broadcast-only")).hasSize(1);
    }

    @Test
    void storesButDoesNotFanOutWhenNoBroadcastClientsAreConfigured() throws Exception {
        GmailService service = service("");

        service.deliver(user, message("<quiet@example.com>", "Quiet"), 5L);

        verify(repository).save(any(EmailMessageEntity.class));
        assertThat(logins.get()).isZero();
    }

    @Test
    void neitherStoresNorForwardsAMessageAlreadyStoredForThatUser() throws Exception {
        webhook("/one", exchange -> 200);
        when(repository.existsByUserIdAndMessageId(any(), anyString())).thenReturn(true);
        GmailService service = service(url("/one"));

        service.deliver(user, message("<dupe@example.com>", "Seen before"), 2L);

        verify(repository, never()).save(any(EmailMessageEntity.class));
        assertThat(received.getOrDefault("/one", List.of())).isEmpty();
    }

    @Test
    void refreshesTheTokenOnceAndRetriesAfterAnUnauthorizedWebhook() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        webhook("/guarded", exchange -> attempts.incrementAndGet() == 1 ? 401 : 200);
        GmailService service = service(url("/guarded"));

        service.deliver(user, message("<retry@example.com>", "Retried"), 3L);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(logins.get()).isEqualTo(2);
        assertThat(authorizations.get("/guarded")).containsExactly("Bearer token-1", "Bearer token-2");
    }

    @Test
    void keepsDeliveringToTheRemainingClientsWhenOneWebhookIsUnreachable() throws Exception {
        webhook("/healthy", exchange -> 200);
        // Port 1 is not listening: the first client fails at the transport level, not with a status.
        GmailService service = service("http://127.0.0.1:1/dead," + url("/healthy"));

        service.deliver(user, message("<partial@example.com>", "Partial"), 4L);

        assertThat(received.get("/healthy")).hasSize(1);
    }

    private GmailService service(String broadcastClients) {
        WebhookSender sender = new WebhookSender(
                "http://127.0.0.1:" + authServer.getAddress().getPort(),
                "gmail-general", "gmail-secret", NO_OP_LOGGER);
        return new GmailService(broadcastClients, repository, subscriptions, sender, NO_OP_LOGGER);
    }

    private static GmailMessage message(String id, String subject) {
        return message(id, subject, "owner@example.com");
    }

    private static GmailMessage message(String id, String subject, String account) {
        return new GmailMessage(id, account, subject, "alice@example.com", account,
                Instant.parse("2026-06-27T15:30:45Z").toEpochMilli(), "body");
    }

    private void webhook(String path, StatusDecider status) {
        webhookServer.createContext(path, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorizations.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>())
                    .add(exchange.getRequestHeaders().getFirst("Authorization"));
            int code = status.statusFor(exchange);
            if (code >= 200 && code < 300) {
                received.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).add(body);
            }
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + webhookServer.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface StatusDecider {
        int statusFor(HttpExchange exchange);
    }
}

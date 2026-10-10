package dev.orwell.google.gmail;

import com.sun.net.httpserver.HttpExchange;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
import dev.orwell.logging.Logger;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.orwell.testing.http.JsonStubServer.respond;
import static dev.orwell.google.gmail.GmailTestFixtures.mail;
import static dev.orwell.google.gmail.GmailTestFixtures.subscription;
import static dev.orwell.google.gmail.GmailTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cursor-tracked delivery: which messages a subscription is sent, in what order, and where its
 * cursor ends up when a receiver fails. This is the behaviour that makes a subscriber which was
 * down catch up rather than lose mail, so the failure cases are the point of the class.
 */
class WebhookDeliveryJobTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private JsonStubServer authServer;
    private JsonStubServer webhookServer;
    private final Map<String, List<String>> received = new ConcurrentHashMap<>();

    private WebhookSubscriptionRepository subscriptions;
    private EmailMessageRepository mails;
    private UserRepository users;
    private UserEntity bob;

    @BeforeEach
    void startServers() throws IOException {
        subscriptions = mock(WebhookSubscriptionRepository.class);
        mails = mock(EmailMessageRepository.class);
        users = mock(UserRepository.class);
        bob = user(1L, "bob@example.com", "bob-client");
        when(users.findById(1L)).thenReturn(Optional.of(bob));
        when(subscriptions.save(any(WebhookSubscriptionEntity.class)))
                .thenAnswer(call -> call.getArgument(0));

        authServer = JsonStubServer.start();
        authServer.on("/def/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{\"clientId\":\"gmail-general\",\"token\":\"token\"}");
        });

        webhookServer = JsonStubServer.start();
    }

    @AfterEach
    void stopServers() {
        authServer.close();
        webhookServer.close();
    }

    @Test
    void deliversEverythingAfterTheCursorInOrderAndAdvancesIt() throws Exception {
        webhook("/sink", exchange -> 200);
        WebhookSubscriptionEntity subscription = subscription(1L, bob, url("/sink"), 10L);
        pending(subscription, mail(11L, bob, "<a@example.com>", "First"),
                mail(12L, bob, "<b@example.com>", "Second"));

        job().deliverPending();

        assertThat(subjectsReceivedOn("/sink")).containsExactly("First", "Second");
        assertThat(subscription.getLastDeliveredId()).isEqualTo(12L);
    }

    /** Nothing new since the cursor means no delivery and no login. */
    @Test
    void deliversNothingWhenTheCursorIsCaughtUp() throws Exception {
        webhook("/sink", exchange -> 200);
        WebhookSubscriptionEntity subscription = subscription(1L, bob, url("/sink"), 10L);
        pending(subscription);

        job().deliverPending();

        assertThat(received.getOrDefault("/sink", List.of())).isEmpty();
        assertThat(subscription.getLastDeliveredId()).isEqualTo(10L);
    }

    /**
     * The core durability property: a rejected message leaves the cursor behind it, so the next
     * round re-sends it instead of skipping past. Previously this message was lost outright.
     */
    @Test
    void leavesTheCursorBeforeARejectedMessageSoItIsRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        // Fails the first time it is asked, succeeds afterwards — a receiver that was briefly down.
        webhook("/flaky", exchange -> attempts.incrementAndGet() == 1 ? 500 : 200);
        WebhookSubscriptionEntity subscription = subscription(1L, bob, url("/flaky"), 10L);
        pending(subscription, mail(11L, bob, "<a@example.com>", "First"),
                mail(12L, bob, "<b@example.com>", "Second"));

        job().deliverPending();
        assertThat(subscription.getLastDeliveredId()).isEqualTo(10L);
        assertThat(received.getOrDefault("/flaky", List.of())).isEmpty();

        // Second round: the receiver is healthy again and catches up on everything it missed.
        job().deliverPending();

        assertThat(subjectsReceivedOn("/flaky")).containsExactly("First", "Second");
        assertThat(subscription.getLastDeliveredId()).isEqualTo(12L);
    }

    /** A failure must not let later mail overtake the message that failed. */
    @Test
    void stopsAtTheFirstFailureRatherThanDeliveringOutOfOrder() throws Exception {
        webhook("/halting", exchange -> received.getOrDefault("/halting", List.of()).isEmpty()
                ? 200 : 500);
        WebhookSubscriptionEntity subscription = subscription(1L, bob, url("/halting"), 0L);
        pending(subscription, mail(1L, bob, "<a@example.com>", "First"),
                mail(2L, bob, "<b@example.com>", "Second"),
                mail(3L, bob, "<c@example.com>", "Third"));

        job().deliverPending();

        assertThat(subjectsReceivedOn("/halting")).containsExactly("First");
        assertThat(subscription.getLastDeliveredId()).isEqualTo(1L);
    }

    /** One broken receiver must not stall the others. */
    @Test
    void keepsDeliveringToOtherSubscriptionsWhenOneIsUnreachable() throws Exception {
        webhook("/healthy", exchange -> 200);
        WebhookSubscriptionEntity dead = subscription(1L, bob, "http://127.0.0.1:1/dead", 10L);
        WebhookSubscriptionEntity healthy = subscription(2L, bob, url("/healthy"), 10L);
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(dead, healthy));
        when(mails.findByUserIdAndIdGreaterThanOrderByIdAsc(eq(1L), eq(10L), any()))
                .thenReturn(List.of(mail(11L, bob, "<a@example.com>", "First")));

        job().deliverPending();

        assertThat(subjectsReceivedOn("/healthy")).containsExactly("First");
        assertThat(dead.getLastDeliveredId()).isEqualTo(10L);
        assertThat(healthy.getLastDeliveredId()).isEqualTo(11L);
    }

    @Test
    void stampsTheOwningAccountOnEveryDeliveredPayload() throws Exception {
        webhook("/sink", exchange -> 200);
        WebhookSubscriptionEntity subscription = subscription(1L, bob, url("/sink"), 0L);
        pending(subscription, mail(1L, bob, "<a@example.com>", "Hello"));

        job().deliverPending();

        assertThat(received.get("/sink").get(0)).contains("\"account\":\"bob@example.com\"");
    }

    /**
     * A subscriber that accepts the connection and then never answers must cost one timeout, not
     * the round. The per-subscription {@code catch} in {@code deliverPending} isolates a
     * subscription that <em>fails</em>, but a hang is not an exception — before the sender bounded
     * its requests, this loop never advanced, so one subscriber stopped delivery for everyone. The
     * delivery job also shares its {@code @Scheduled} thread with {@code ImapMailPoller}, so the
     * same stall stopped mail arriving at all.
     */
    @Test
    void aSubscriberThatNeverRespondsDoesNotBlockTheOthers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // Its own server on purpose: JsonStubServer dispatches on a single thread, so a handler
        // that blocks would stall every other route on the same instance and this test would be
        // proving the harness rather than the sender.
        JsonStubServer blackHole = JsonStubServer.start();
        blackHole.on("/black-hole", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // Accept, then hold the connection open without answering, exactly as a dead-but-
            // listening host does. Released in the finally below so the server can shut down.
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        webhook("/sink", exchange -> 200);

        WebhookSubscriptionEntity hung = subscription(
                1L, bob, "http://127.0.0.1:" + blackHole.port() + "/black-hole", 0L);
        WebhookSubscriptionEntity healthy = subscription(2L, bob, url("/sink"), 0L);
        EmailMessageEntity message = mail(1L, bob, "<a@example.com>", "Hello");
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(hung, healthy));
        when(mails.findByUserIdAndIdGreaterThanOrderByIdAsc(eq(1L), any(), any()))
                .thenAnswer(call -> (long) call.getArgument(1) < 1L ? List.of(message) : List.of());

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> job(Duration.ofMillis(500)).deliverPending(),
                    "a hung subscriber blocked the delivery round");
        } finally {
            release.countDown();
            blackHole.close();
        }

        // The point: the subscription behind the hung one still got its mail...
        assertThat(subjectsReceivedOn("/sink")).containsExactly("Hello");
        assertThat(healthy.getLastDeliveredId()).isEqualTo(1L);
        // ...and the hung one kept its cursor, so it retries rather than losing the message.
        assertThat(hung.getLastDeliveredId()).isEqualTo(0L);
    }

    private WebhookDeliveryJob job() {
        return job(Duration.ofSeconds(30));
    }

    private WebhookDeliveryJob job(Duration requestTimeout) {
        WebhookSender sender = new WebhookSender(
                "http://127.0.0.1:" + authServer.port(),
                "gmail-general", "gmail-secret", NO_OP_LOGGER, requestTimeout);
        return new WebhookDeliveryJob(subscriptions, mails, users, sender,
                GmailTestFixtures.payloads(), NO_OP_LOGGER);
    }

    /** Stubs one subscription and the mail waiting beyond its cursor. */
    private void pending(WebhookSubscriptionEntity subscription, EmailMessageEntity... mail) {
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(subscription));
        // Answer dynamically: the cursor moves during a round, and later calls must reflect that.
        when(mails.findByUserIdAndIdGreaterThanOrderByIdAsc(eq(1L), any(), any()))
                .thenAnswer(call -> {
                    long cursor = call.getArgument(1);
                    List<EmailMessageEntity> remaining = new ArrayList<>();
                    for (EmailMessageEntity candidate : mail) {
                        if (candidate.getId() > cursor) {
                            remaining.add(candidate);
                        }
                    }
                    return remaining;
                });
    }

    private List<String> subjectsReceivedOn(String path) {
        return received.getOrDefault(path, List.of()).stream()
                .map(body -> body.replaceAll("(?s).*\"subject\":\"([^\"]*)\".*", "$1")).toList();
    }

    private void webhook(String path, StatusDecider status) {
        webhookServer.on(path, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int code = status.statusFor(exchange);
            if (code >= 200 && code < 300) {
                received.computeIfAbsent(path, ignored -> new CopyOnWriteArrayList<>()).add(body);
            }
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + webhookServer.port() + path;
    }


    @FunctionalInterface
    private interface StatusDecider {
        int statusFor(HttpExchange exchange);
    }
}

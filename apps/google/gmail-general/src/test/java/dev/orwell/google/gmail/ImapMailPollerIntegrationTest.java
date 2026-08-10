package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.testing.PostgresIntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end coverage of the poll-and-serve path: mailboxes registered through the admin API are
 * polled from an in-process GreenMail IMAP server by the scheduled {@link ImapMailPoller},
 * persisted to a real (Testcontainers) Postgres, and readable back through
 * {@link dev.orwell.google.gmail.controller.MailController} — each consumer seeing only its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImapMailPollerIntegrationTest extends PostgresIntegrationTest {
    private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.IMAP.dynamicPort());
    private static GreenMailUser bob;
    private static GreenMailUser carol;
    private static GreenMailUser erin;
    private static GreenMailUser heidi;

    @BeforeAll
    static void startGreenMail() {
        GREEN_MAIL.start();
        bob = GREEN_MAIL.setUser("bob@example.com", "bob@example.com", "bob-secret");
        carol = GREEN_MAIL.setUser("carol@example.com", "carol@example.com", "carol-secret");
        erin = GREEN_MAIL.setUser("erin@example.com", "erin@example.com", "erin-secret");
        heidi = GREEN_MAIL.setUser("heidi@example.com", "heidi@example.com", "heidi-secret");
        GREEN_MAIL.setUser("dave@example.com", "dave@example.com", "dave-real-secret");
    }

    @AfterAll
    static void stopGreenMail() {
        GREEN_MAIL.stop();
    }

    @DynamicPropertySource
    static void gmailProperties(DynamicPropertyRegistry registry) {
        registry.add("orwell.auth.base-url", () -> "http://localhost:1");
        registry.add("gmail.auth.client-id", () -> "gmail-general");
        registry.add("gmail.auth.client-secret", () -> "");
        registry.add("gmail.webhook-clients", () -> "");
        registry.add("gmail.route-prefix", () -> "");
        registry.add("gmail.poll-interval-seconds", () -> 1);
        registry.add("gmail.poll-concurrency", () -> 4);
        registry.add("gmail.delivery-interval-seconds", () -> 1);
        registry.add("gmail.imap.host", () -> "127.0.0.1");
        registry.add("gmail.imap.port", () -> GREEN_MAIL.getImap().getPort());
        registry.add("gmail.imap.ssl", () -> false);
        registry.add("gmail.imap.folder", () -> "INBOX");
    }

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private dev.orwell.google.gmail.repository.ImapCheckpointRepository checkpoints;

    @org.springframework.beans.factory.annotation.Autowired
    private dev.orwell.google.gmail.repository.UserRepository userRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersMailboxesThenPollsStoresAndServesEachToItsOwnConsumer() throws Exception {
        register("bob@example.com", "bob-client", "bob-secret");
        register("carol@example.com", "carol-client", "carol-secret");

        // A newly registered mailbox starts from its current head, so that adding a user does not
        // replay its entire history. Mail delivered before that first poll is therefore skipped by
        // design — wait for the checkpoint to be established before sending anything.
        awaitFirstPollOf("bob-client", "carol-client");

        bob.deliver(newMessage("bob@example.com", "Bob meeting notes", "Bob's plain body."));
        carol.deliver(newMessage("carol@example.com", "Carol invoice", "Carol's plain body."));

        JsonNode bobLatest = awaitLatestContaining("bob-client", "Bob meeting notes", 30_000);
        assertThat(bobLatest.get("body").asText()).contains("Bob's plain body.");
        // threadId was dropped from the stored message and the response; keep it gone.
        assertThat(bobLatest.has("threadId")).isFalse();
        JsonNode carolLatest = awaitLatestContaining("carol-client", "Carol invoice", 30_000);
        assertThat(carolLatest.get("body").asText()).contains("Carol's plain body.");

        // The central property of the redesign: neither consumer can see the other's mail, and
        // there is no parameter that would let them ask for it.
        JsonNode bobList = objectMapper.readTree(httpGet("/mails", "bob-client").body());
        assertThat(subjectsOf(bobList)).containsExactly("Bob meeting notes");
        JsonNode carolList = objectMapper.readTree(httpGet("/mails", "carol-client").body());
        assertThat(subjectsOf(carolList)).containsExactly("Carol invoice");

        // The checkpoint cursor stays scoped too: ids come from one sequence shared by all users,
        // so a cursor low enough to cover everything ever stored must still yield only the
        // caller's own mail.
        JsonNode carolFromStart = objectMapper.readTree(
                httpGet("/mails/latest?checkpoint=0", "carol-client").body());
        assertThat(subjectsOf(carolFromStart)).containsExactly("Carol invoice");
    }

    @Test
    void rejectsAnAuthenticatedClientThatOwnsNoMailbox() throws Exception {
        assertThat(httpGet("/mails", "client-with-no-mailbox").statusCode()).isEqualTo(403);
    }

    /**
     * A server that reassigns UIDVALIDITY has invalidated every UID recorded against it. Resuming
     * from the stored cursor would then mean resuming at a number that indexes nothing, so the
     * poller must notice and resync from the mailbox head. Driven by corrupting the stored row
     * rather than by making GreenMail reissue UIDVALIDITY, which it gives no handle for: the
     * poller's input is the mismatch, and that is what is reproduced here.
     */
    @Test
    void resyncsFromTheMailboxHeadWhenUidValidityNoLongerMatches() throws Exception {
        register("heidi@example.com", "heidi-client", "heidi-secret");
        awaitFirstPollOf("heidi-client");

        Long heidiId = userRepository.findByClientId("heidi-client").orElseThrow().getId();
        var stored = checkpoints.findByUserIdAndFolder(heidiId, "INBOX").orElseThrow();
        long realUidValidity = stored.getUidValidity();
        // A cursor far beyond anything this mailbox will ever issue, tagged to a generation that no
        // longer exists. Without the resync it would swallow every future message silently.
        stored.resync(realUidValidity + 987_654L, 999_999L, java.time.Instant.now());
        checkpoints.save(stored);

        awaitCheckpointUidValidity(heidiId, realUidValidity, 30_000);

        heidi.deliver(newMessage("heidi@example.com", "Heidi after resync", "Heidi's body."));

        JsonNode latest = awaitLatestContaining("heidi-client", "Heidi after resync", 30_000);
        assertThat(latest.get("subject").asText()).isEqualTo("Heidi after resync");
        assertThat(checkpoints.findByUserIdAndFolder(heidiId, "INBOX").orElseThrow().getLastUid())
                .isLessThan(999_999L);
    }

    private void awaitCheckpointUidValidity(Long userId, long expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (checkpoints.findByUserIdAndFolder(userId, "INBOX")
                    .filter(row -> row.getUidValidity() == expected).isPresent()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for the poller to resync UIDVALIDITY back to " + expected);
    }

    /**
     * The property that makes running many mailboxes in one service safe. Every user is polled on
     * the same scheduled tick, so a mailbox whose credentials the server rejects must be contained:
     * if one bad password could end the round, a single stale app password would silently starve
     * every other mailbox and the only symptom would be mail that stops arriving.
     */
    @Test
    void keepsPollingOtherMailboxesWhenOneMailboxCredentialIsWrong() throws Exception {
        register("dave@example.com", "dave-client", "dave-WRONG-secret");
        register("erin@example.com", "erin-client", "erin-secret");
        awaitFirstPollOf("erin-client");

        erin.deliver(newMessage("erin@example.com", "Erin still delivered", "Erin's body."));

        JsonNode erinLatest = awaitLatestContaining("erin-client", "Erin still delivered", 30_000);
        assertThat(erinLatest.get("subject").asText()).isEqualTo("Erin still delivered");
        // Dave's mailbox never authenticated, so it stored nothing — and said so quietly rather
        // than taking the round down with it.
        assertThat(httpGet("/mails/latest", "dave-client").statusCode()).isEqualTo(204);
    }

    /**
     * A user exists from the moment it is created but has no secret until a second call. That gap
     * is normal, not an error, so the poller must skip it rather than fail the round.
     */
    @Test
    void skipsAUserWithNoSecretWithoutFailingTheRound() throws Exception {
        HttpResponse<String> created = httpSend("POST", "/users", "frank-client",
                "{\"email\":\"frank@example.com\",\"clientId\":\"frank-client\"}");
        assertThat(created.statusCode()).isEqualTo(201);

        register("grace@example.com", "grace-client", "grace-secret");
        GREEN_MAIL.setUser("grace@example.com", "grace@example.com", "grace-secret");
        awaitFirstPollOf("grace-client");

        // Frank is simply not polled; the service keeps running and serving everyone else.
        assertThat(httpGet("/mails/latest", "frank-client").statusCode()).isEqualTo(204);
        assertThat(httpGet("/mails", "grace-client").statusCode()).isEqualTo(200);
    }

    /**
     * Waits until every named consumer answers {@code /mails/latest}, which it only does once the
     * poller has run for that user. Several poll intervals, so the wait is on an observable
     * condition rather than a fixed sleep.
     */
    private void awaitFirstPollOf(String... clientIds) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        for (String clientId : clientIds) {
            while (System.currentTimeMillis() < deadline) {
                if (httpGet("/mails/latest", clientId).statusCode() == 204) {
                    break;
                }
                Thread.sleep(100);
            }
        }
        // The 204 above only proves the mailbox is reachable and empty; give the poller one more
        // full interval to write the checkpoint row before any mail is delivered.
        Thread.sleep(2_000);
    }

    private void register(String email, String clientId, String imapPassword) throws Exception {
        HttpResponse<String> created = httpSend("POST", "/users", clientId,
                "{\"email\":\"%s\",\"clientId\":\"%s\"}".formatted(email, clientId));
        assertThat(created.statusCode()).isEqualTo(201);
        long userId = objectMapper.readTree(created.body()).get("id").asLong();

        HttpResponse<String> secret = httpSend("PUT", "/users/" + userId + "/secret", clientId,
                "{\"imapPassword\":\"%s\"}".formatted(imapPassword));
        assertThat(secret.statusCode()).isEqualTo(204);
    }

    private static java.util.List<String> subjectsOf(JsonNode array) {
        assertThat(array.isArray()).isTrue();
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.get("subject").asText()).toList();
    }

    private MimeMessage newMessage(String to, String subject, String body) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("alice@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject(subject);
        message.setText(body);
        message.saveChanges();
        return message;
    }

    private JsonNode awaitLatestContaining(String clientId, String subjectMarker, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = httpGet("/mails/latest", clientId);
            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                if (node.has("subject") && subjectMarker.equals(node.get("subject").asText())) {
                    return node;
                }
            }
            Thread.sleep(200);
        }
        fail("Timed out waiting for /mails/latest to report a message with subject: " + subjectMarker);
        throw new IllegalStateException("unreachable");
    }

    private HttpResponse<String> httpGet(String path, String clientId) throws IOException, InterruptedException {
        return httpSend("GET", path, clientId, null);
    }

    private HttpResponse<String> httpSend(String method, String path, String clientId, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
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

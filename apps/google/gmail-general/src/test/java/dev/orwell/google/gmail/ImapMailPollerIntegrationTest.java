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
 * End-to-end coverage of the poll-and-serve path: a message pushed to an in-process GreenMail IMAP
 * server is picked up by the scheduled {@link ImapMailPoller}, persisted to a real (Testcontainers)
 * Postgres, and readable back through {@link dev.orwell.google.gmail.controller.MailController}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImapMailPollerIntegrationTest extends PostgresIntegrationTest {
    private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.IMAP.dynamicPort());
    private static GreenMailUser user;

    @BeforeAll
    static void startGreenMail() {
        GREEN_MAIL.start();
        user = GREEN_MAIL.setUser("bob@example.com", "bob@example.com", "secret");
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
        registry.add("gmail.imap.host", () -> "127.0.0.1");
        registry.add("gmail.imap.port", () -> GREEN_MAIL.getImap().getPort());
        registry.add("gmail.imap.ssl", () -> false);
        registry.add("gmail.imap.username", () -> "bob@example.com");
        registry.add("gmail.imap.password", () -> "secret");
        registry.add("gmail.imap.folder", () -> "INBOX");
    }

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pollsStoresAndServesNewMail() throws Exception {
        user.deliver(newMessage("Meeting notes", "The plain body."));

        JsonNode latest = awaitLatestContaining("Meeting notes", 20_000);
        assertThat(latest.get("subject").asText()).isEqualTo("Meeting notes");
        assertThat(latest.get("body").asText()).contains("The plain body.");

        long checkpoint = latest.get("id").asLong() - 1;
        JsonNode sinceCheckpoint = objectMapper.readTree(
                httpGet("/mails/latest?checkpoint=" + checkpoint).body());
        assertThat(sinceCheckpoint.isArray()).isTrue();
        assertThat(sinceCheckpoint).hasSize(1);
        assertThat(sinceCheckpoint.get(0).get("subject").asText()).isEqualTo("Meeting notes");

        JsonNode list = objectMapper.readTree(httpGet("/mails").body());
        assertThat(list.isArray()).isTrue();
        assertThat(list).isNotEmpty();
    }

    private MimeMessage newMessage(String subject, String body) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("alice@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("bob@example.com"));
        message.setSubject(subject);
        message.setText(body);
        message.saveChanges();
        return message;
    }

    private JsonNode awaitLatestContaining(String subjectMarker, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = httpGet("/mails/latest");
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

    private HttpResponse<String> httpGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Client-Id", "test-client")
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

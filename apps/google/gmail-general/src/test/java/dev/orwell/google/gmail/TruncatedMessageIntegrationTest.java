package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.testing.PostgresIntegrationTest;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
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
 * The over-the-cap path, against a real IMAP server rather than an in-memory message.
 *
 * <p>Its own class because {@code GMAIL_MAX_MESSAGE_BYTES} is fixed for a Spring context, and this
 * behaviour only appears below the default 25 MB if the cap is lowered. Worth the separate context:
 * the truncated path is the one where nothing is downloaded and the message is described from the
 * IMAP {@code BODYSTRUCTURE} instead, so it exercises code that a re-parsed local message never
 * reaches.
 *
 * <p>What must hold is that a too-large message is <em>stored anyway</em>. The poller advances its
 * UID cursor past every message it has handled, so anything skipped here would be skipped
 * permanently — the silent mail loss the README warns about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TruncatedMessageIntegrationTest extends PostgresIntegrationTest {
    private static final int CAP_BYTES = 8 * 1024;

    private static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.IMAP.dynamicPort());
    private static GreenMailUser nina;

    @BeforeAll
    static void startGreenMail() {
        GREEN_MAIL.start();
        nina = GREEN_MAIL.setUser("nina@example.com", "nina@example.com", "nina-secret");
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
        registry.add("gmail.max-message-bytes", () -> (long) CAP_BYTES);
        registry.add("gmail.public-base-url", () -> "");
        registry.add("gmail.delivery-interval-seconds", () -> 3600);
        registry.add("gmail.imap.host", () -> "127.0.0.1");
        registry.add("gmail.imap.port", () -> GREEN_MAIL.getImap().getPort());
        registry.add("gmail.imap.ssl", () -> false);
        registry.add("gmail.imap.folder", () -> "INBOX");
    }

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void storesAnOversizeMessageWithoutItsAttachmentBytes() throws Exception {
        register("nina@example.com", "nina-client", "nina-secret");
        awaitFirstPoll("nina-client");

        byte[] big = new byte[64 * 1024];
        java.util.Arrays.fill(big, (byte) 'A');
        nina.deliver(messageWithAttachment("nina@example.com", "Nina oversize", big));

        JsonNode latest = awaitLatestContaining("nina-client", "Nina oversize");

        // Stored, not skipped — the cursor has moved past this message and will not return to it.
        assertThat(latest.get("truncated").asBoolean()).isTrue();
        // Everything that does not need the body survives: the message is still readable mail.
        assertThat(latest.get("body").asText()).contains("see attached");
        assertThat(latest.get("headers").get("Subject").get(0).asText()).isEqualTo("Nina oversize");
        assertThat(latest.get("sizeBytes").asLong()).isGreaterThan(CAP_BYTES);

        // The attachment is described from the IMAP structure alone, without downloading it.
        JsonNode attachment = latest.get("attachments").get(0);
        assertThat(attachment.get("filename").asText()).isEqualTo("big.bin");
        assertThat(attachment.get("mimeType").asText()).isEqualTo("application/octet-stream");
        // Flagged before the client asks, so it need not discover the 409 by trying.
        assertThat(attachment.get("available").asBoolean()).isFalse();

        // 409, not 404: the part is real and indexed; what is missing is the content.
        assertThat(httpGet(attachment.get("url").asText(), "nina-client").statusCode()).isEqualTo(409);
    }

    private MimeMessage messageWithAttachment(String to, String subject, byte[] attachment)
            throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("alice@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        message.setSubject(subject);
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("see attached", "utf-8");
        mixed.addBodyPart(text);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new DataHandler(
                new ByteArrayDataSource(attachment, "application/octet-stream")));
        file.setFileName("big.bin");
        file.setDisposition(Part.ATTACHMENT);
        mixed.addBodyPart(file);
        message.setContent(mixed);
        message.saveChanges();
        return message;
    }

    private void register(String email, String clientId, String imapPassword) throws Exception {
        HttpResponse<String> created = httpSend("POST", "/users", clientId,
                "{\"email\":\"%s\",\"clientId\":\"%s\"}".formatted(email, clientId));
        assertThat(created.statusCode()).isEqualTo(201);
        long userId = objectMapper.readTree(created.body()).get("id").asLong();
        assertThat(httpSend("PUT", "/users/" + userId + "/secret", clientId,
                "{\"imapPassword\":\"%s\"}".formatted(imapPassword)).statusCode()).isEqualTo(204);
    }

    private void awaitFirstPoll(String clientId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (httpGet("/mails/latest", clientId).statusCode() == 204) {
                break;
            }
            Thread.sleep(100);
        }
        // The 204 proves the mailbox is reachable and empty; give the poller one more interval to
        // write its checkpoint before delivering anything.
        Thread.sleep(2_000);
    }

    private JsonNode awaitLatestContaining(String clientId, String subject) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = httpGet("/mails/latest", clientId);
            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                if (node.has("subject") && subject.equals(node.get("subject").asText())) {
                    return node;
                }
            }
            Thread.sleep(200);
        }
        fail("Timed out waiting for /mails/latest to report: " + subject);
        throw new IllegalStateException("unreachable");
    }

    private HttpResponse<String> httpGet(String path, String clientId)
            throws IOException, InterruptedException {
        return httpSend("GET", path, clientId, null);
    }

    private HttpResponse<String> httpSend(String method, String path, String clientId, String body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return httpClient.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Client-Id", clientId)
                .header("Authorization", "Bearer valid-token")
                .header("Content-Type", "application/json")
                .method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
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

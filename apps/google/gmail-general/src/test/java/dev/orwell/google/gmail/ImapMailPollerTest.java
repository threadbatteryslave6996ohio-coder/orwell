package dev.orwell.google.gmail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImapMailPollerTest {
    private static final Session SESSION = Session.getInstance(new Properties());

    @Test
    void mapsPlainTextMessageToStorageDto() throws Exception {
        String raw = "Message-ID: <abc@example.com>\r\n"
                + "Subject: Hello there\r\n"
                + "From: Alice <alice@example.com>\r\n"
                + "To: bob@example.com\r\n"
                + "Date: Wed, 22 Jul 2026 10:00:00 +0000\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Body text here.\r\n";
        MimeMessage message = new MimeMessage(SESSION,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));

        GmailMessage result = ImapMailPoller.toGmailMessage(message, 42L);

        assertEquals("<abc@example.com>", result.id());
        assertEquals("Hello there", result.subject());
        assertTrue(result.from().contains("alice@example.com"), result.from());
        assertTrue(result.to().contains("bob@example.com"), result.to());
        assertEquals("Body text here.", result.body().strip());
    }

    @Test
    void fallsBackToUidWhenNoMessageId() throws Exception {
        String raw = "Subject: No id\r\n\r\nbody\r\n";
        MimeMessage message = new MimeMessage(SESSION,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));

        GmailMessage result = ImapMailPoller.toGmailMessage(message, 7L);

        assertEquals("uid-7", result.id());
    }

    @Test
    void prefersPlainTextPartOfMultipart() throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setHeader("Message-ID", "<m@example.com>");
        message.setSubject("multi");
        MimeMultipart multipart = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("the plain part", "utf-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>the html part</p>", "text/html; charset=utf-8");
        multipart.addBodyPart(text);
        multipart.addBodyPart(html);
        message.setContent(multipart);
        message.saveChanges();

        GmailMessage result = ImapMailPoller.toGmailMessage(message, 1L);

        assertEquals("the plain part", result.body().strip());
    }
}

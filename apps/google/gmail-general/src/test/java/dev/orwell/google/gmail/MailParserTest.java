package dev.orwell.google.gmail;

import dev.orwell.google.gmail.ParsedMail.ParsedAttachment;
import jakarta.activation.DataHandler;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a polled message yields. The cases that matter are the ones the previous parser silently
 * dropped: the HTML alternative, every header past the four the DTO used to have, and any part
 * that was not the first {@code text/plain} one.
 */
class MailParserTest {
    private static final Session SESSION = Session.getInstance(new Properties());
    private static final long NO_CAP = Long.MAX_VALUE;

    @Test
    void mapsPlainTextMessageToStorageDto() throws Exception {
        MimeMessage message = parse("""
                Message-ID: <abc@example.com>\r
                Subject: Hello there\r
                From: Alice <alice@example.com>\r
                To: bob@example.com\r
                Date: Wed, 22 Jul 2026 10:00:00 +0000\r
                Content-Type: text/plain; charset=UTF-8\r
                \r
                Body text here.\r
                """);

        ParsedMail result = MailParser.parse(message, 42L, NO_CAP);

        assertEquals("<abc@example.com>", result.messageId());
        assertEquals("Hello there", result.subject());
        assertTrue(result.from().contains("alice@example.com"), result.from());
        assertTrue(result.to().contains("bob@example.com"), result.to());
        assertEquals("Body text here.", result.bodyText().strip());
        assertThat(result.truncated()).isFalse();
        assertThat(result.rawSource()).isNotNull();
    }

    @Test
    void fallsBackToUidWhenNoMessageId() throws Exception {
        ParsedMail result = MailParser.parse(parse("Subject: No id\r\n\r\nbody\r\n"), 7L, NO_CAP);

        assertEquals("uid-7", result.messageId());
    }

    /**
     * The plain part is still the {@code body}, but the HTML alternative is no longer discarded —
     * for most senders it is the only rendering that carries the formatting and links.
     */
    @Test
    void keepsBothTheTextAndTheHtmlAlternative() throws Exception {
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

        ParsedMail result = MailParser.parse(message, 1L, NO_CAP);

        assertEquals("the plain part", result.bodyText().strip());
        assertEquals("<p>the html part</p>", result.bodyHtml().strip());
        assertThat(result.attachments()).isEmpty();
    }

    @Test
    void keepsAnHtmlOnlyMessageAsHtmlWithNoPlainBody() throws Exception {
        MimeMessage message = parse("""
                Message-ID: <html-only@example.com>\r
                Subject: Rich\r
                Content-Type: text/html; charset=UTF-8\r
                \r
                <b>only html</b>\r
                """);

        ParsedMail result = MailParser.parse(message, 3L, NO_CAP);

        assertEquals("", result.bodyText());
        assertEquals("<b>only html</b>", result.bodyHtml().strip());
    }

    /**
     * The headers the old four-field DTO threw away. {@code Received} appears twice on purpose: it
     * is a delivery path, and a map-shaped store would have kept only one hop.
     */
    @Test
    void keepsEveryHeaderInOrderIncludingRepeatedOnes() throws Exception {
        MimeMessage message = parse("""
                Received: from mx2.example.com\r
                Received: from mx1.example.com\r
                Message-ID: <full@example.com>\r
                Subject: Everything\r
                From: alice@example.com\r
                To: bob@example.com\r
                Cc: carol@example.com\r
                Bcc: dan@example.com\r
                Reply-To: noreply@example.com\r
                X-Custom-Thing: keep me\r
                \r
                body\r
                """);

        ParsedMail result = MailParser.parse(message, 4L, NO_CAP);

        assertThat(result.headers()).extracting(ParsedMail.ParsedHeader::name)
                .containsSubsequence("Received", "Received", "Message-ID", "Cc", "Bcc",
                        "Reply-To", "X-Custom-Thing");
        assertThat(result.headers()).filteredOn(header -> header.name().equals("Received"))
                .extracting(ParsedMail.ParsedHeader::value)
                // Order preserved: the top Received line is the last hop, and reversing them would
                // reverse the delivery path a reader reconstructs from it.
                .containsExactly("from mx2.example.com", "from mx1.example.com");
        assertThat(valueOf(result, "Cc")).isEqualTo("carol@example.com");
        assertThat(valueOf(result, "X-Custom-Thing")).isEqualTo("keep me");
    }

    @Test
    void indexesAttachmentsWithTheirNamesTypesAndDecodedSizes() throws Exception {
        byte[] pdf = "%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8);
        MimeMessage message = withAttachment("report.pdf", "application/pdf", pdf, Part.ATTACHMENT, null);

        ParsedMail result = MailParser.parse(message, 5L, NO_CAP);

        assertThat(result.bodyText().strip()).isEqualTo("see attached");
        assertThat(result.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.filename()).isEqualTo("report.pdf");
            assertThat(attachment.mimeType()).isEqualTo("application/pdf");
            // The decoded length, not the base64-inflated one the MIME structure reports.
            assertThat(attachment.sizeBytes()).isEqualTo(pdf.length);
            assertThat(attachment.inline()).isFalse();
        });
    }

    /** An embedded image is a part like any other, but a renderer needs to tell it from a file. */
    @Test
    void marksAnInlineImageAsInlineAndKeepsItsContentId() throws Exception {
        MimeMessage message = withAttachment("logo.png", "image/png",
                new byte[] {1, 2, 3, 4}, Part.INLINE, "<logo-1@example.com>");

        ParsedMail result = MailParser.parse(message, 6L, NO_CAP);

        assertThat(result.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.inline()).isTrue();
            // Unbracketed, because that is the form an HTML body's src="cid:..." carries.
            assertThat(attachment.contentId()).isEqualTo("logo-1@example.com");
            assertThat(attachment.mimeType()).isEqualTo("image/png");
        });
    }

    /**
     * The stored path must find the same bytes back. This is the whole contract between ingestion
     * and download: the index says where a part is, and the archive is the only copy of it.
     */
    @Test
    void resolvesAStoredPartPathBackToItsBytes() throws Exception {
        byte[] pdf = "%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8);
        ParsedMail result = MailParser.parse(
                withAttachment("report.pdf", "application/pdf", pdf, Part.ATTACHMENT, null),
                7L, NO_CAP);

        ParsedAttachment attachment = result.attachments().get(0);
        Part part = MailParser.partAt(result.rawSource(), attachment.partPath());

        try (InputStream stream = part.getInputStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(pdf);
        }
    }

    @Test
    void addressesNestedPartsDistinctly() throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setSubject("nested");
        MimeMultipart mixed = new MimeMultipart("mixed");

        MimeBodyPart alternativePart = new MimeBodyPart();
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("plain", "utf-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>html</p>", "text/html; charset=utf-8");
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        alternativePart.setContent(alternative);
        mixed.addBodyPart(alternativePart);

        mixed.addBodyPart(attachmentPart("a.txt", "text/plain",
                "first".getBytes(StandardCharsets.UTF_8), Part.ATTACHMENT, null));
        mixed.addBodyPart(attachmentPart("b.txt", "text/plain",
                "second".getBytes(StandardCharsets.UTF_8), Part.ATTACHMENT, null));
        message.setContent(mixed);
        message.saveChanges();

        ParsedMail result = MailParser.parse(message, 8L, NO_CAP);

        assertThat(result.bodyText().strip()).isEqualTo("plain");
        assertThat(result.bodyHtml().strip()).isEqualTo("<p>html</p>");
        assertThat(result.attachments()).extracting(ParsedAttachment::partPath)
                .containsExactly("0.2", "0.3");
        // Each path still resolves to its own part, which is what makes the index usable at all.
        assertThat(bytesAt(result, 0)).isEqualTo("first".getBytes(StandardCharsets.UTF_8));
        assertThat(bytesAt(result, 1)).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Over the cap the message is still stored and still described — only its archive is skipped.
     * Losing the message outright would be the failure mode the poller's cursor makes permanent.
     */
    @Test
    void storesAnOversizeMessageWithoutItsRawSource() throws Exception {
        byte[] big = new byte[64 * 1024];
        MimeMessage message = withAttachment("big.bin", "application/octet-stream",
                big, Part.ATTACHMENT, null);

        ParsedMail result = MailParser.parse(message, 9L, 1024L);

        assertThat(result.truncated()).isTrue();
        assertThat(result.rawSource()).isNull();
        // Everything that does not need the body survives.
        assertThat(result.subject()).isEqualTo("with attachment");
        assertThat(result.headers()).isNotEmpty();
        assertThat(result.attachments()).extracting(ParsedAttachment::filename)
                .containsExactly("big.bin");
    }

    private static byte[] bytesAt(ParsedMail mail, int index) throws Exception {
        Part part = MailParser.partAt(mail.rawSource(), mail.attachments().get(index).partPath());
        try (InputStream stream = part.getInputStream()) {
            return stream.readAllBytes();
        }
    }

    private static String valueOf(ParsedMail mail, String name) {
        return mail.headers().stream().filter(header -> header.name().equalsIgnoreCase(name))
                .map(ParsedMail.ParsedHeader::value).findFirst().orElse(null);
    }

    private static MimeMessage withAttachment(String filename, String mimeType, byte[] content,
            String disposition, String contentId) throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setHeader("Message-ID", "<attached@example.com>");
        message.setSubject("with attachment");
        MimeMultipart multipart = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("see attached", "utf-8");
        multipart.addBodyPart(text);
        multipart.addBodyPart(attachmentPart(filename, mimeType, content, disposition, contentId));
        message.setContent(multipart);
        message.saveChanges();
        // Round-trip through bytes so the parser sees a message with real transfer encodings,
        // the way one off the wire arrives, rather than an in-memory object graph.
        return parse(message);
    }

    private static MimeBodyPart attachmentPart(String filename, String mimeType, byte[] content,
            String disposition, String contentId) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(content, mimeType)));
        part.setFileName(filename);
        part.setDisposition(disposition);
        if (contentId != null) {
            part.setHeader("Content-ID", contentId);
        }
        return part;
    }

    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(SESSION,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static MimeMessage parse(MimeMessage message) throws Exception {
        var buffer = new java.io.ByteArrayOutputStream();
        message.writeTo(buffer);
        return new MimeMessage(SESSION, new ByteArrayInputStream(buffer.toByteArray()));
    }

    /** Guards the assumption the multipart tests rest on: paths are 1-based under a "0" root. */
    @Test
    void numbersTopLevelPartsFromOneUnderTheRoot() throws Exception {
        ParsedMail result = MailParser.parse(withAttachment("x.bin", "application/octet-stream",
                new byte[] {9}, Part.ATTACHMENT, null), 10L, NO_CAP);

        assertThat(result.attachments()).extracting(ParsedAttachment::partPath)
                .isEqualTo(List.of("0.2"));
    }
}

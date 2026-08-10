package dev.orwell.google.gmail.controller;

import dev.orwell.google.gmail.AttachmentRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One stored mail as the read API returns it. The same content the webhook payload carries, keyed
 * by the database {@code id} that the read API's cursor uses.
 *
 * @param sizeBytes the size of the whole original message, which is not the sum of the attachment
 *                  sizes: it includes headers, MIME framing, and transfer encoding.
 */
public record MailResponse(
        Long id,
        String messageId,
        String subject,
        String from,
        String to,
        Instant receivedAt,
        String body,
        String bodyHtml,
        Map<String, List<String>> headers,
        List<AttachmentRef> attachments,
        long sizeBytes,
        boolean truncated) {
}

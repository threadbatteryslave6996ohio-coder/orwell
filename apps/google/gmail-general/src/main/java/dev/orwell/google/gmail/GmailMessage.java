package dev.orwell.google.gmail;

import java.util.List;
import java.util.Map;

/**
 * One received mail, as handed to webhook subscribers.
 *
 * <p>{@code account} is the mailbox the message was polled from. It exists so a receiver can tell
 * whose mail it is being handed: a per-mailbox subscription only ever delivers one account, but the
 * legacy {@code GMAIL_WEBHOOK_CLIENTS} broadcast delivers every mailbox to the same URL, and
 * without this field those receivers cannot distinguish them.
 *
 * <p>Built from the stored row rather than straight from the poller, because {@link AttachmentRef}
 * URLs are addressed by database id — the payload cannot exist until the message has one.
 *
 * @param headers     every header the message carried, keyed by name in the order they appeared.
 *                    The value is a list because names repeat: {@code Received} is a delivery path,
 *                    not a single value. Lookup is by the name as sent, matched case-insensitively.
 * @param attachments metadata only; see {@link AttachmentRef} for why the bytes are not here.
 * @param truncated   the message was too large to archive in full, so attachment bytes are not
 *                    retrievable. Headers and text bodies are still complete.
 */
public record GmailMessage(
        String id,
        String account,
        String subject,
        String from,
        String to,
        long receivedAt,
        String body,
        String bodyHtml,
        Map<String, List<String>> headers,
        List<AttachmentRef> attachments,
        boolean truncated) {
}

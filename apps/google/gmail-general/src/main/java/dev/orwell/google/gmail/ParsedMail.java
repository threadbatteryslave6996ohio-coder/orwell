package dev.orwell.google.gmail;

import java.util.List;

/**
 * Everything one polled message yields, as handed from {@link MailParser} to {@link GmailService}
 * for storage. This is the <em>ingestion</em> shape; {@link GmailMessage} is the delivery shape and
 * is built from the stored row afterwards, because it carries the database id that attachment URLs
 * are addressed by.
 *
 * <p>{@code rawSource} is the complete RFC 822 source. It is what makes "nothing is lost" true:
 * the parsed fields below are an index over it, not a replacement for it, so a part this parser
 * mishandles is still recoverable. It is {@code null} exactly when {@code truncated} is true — the
 * message was larger than {@code GMAIL_MAX_MESSAGE_BYTES} and was indexed from its IMAP structure
 * without downloading the body.
 *
 * @param rawSizeBytes the server-reported RFC822 size, recorded even when the source was not
 *                     stored, so an operator can see what a truncated row would have cost.
 */
public record ParsedMail(
        String messageId,
        String subject,
        String from,
        String to,
        long receivedAt,
        String bodyText,
        String bodyHtml,
        List<ParsedHeader> headers,
        List<ParsedAttachment> attachments,
        byte[] rawSource,
        long rawSizeBytes,
        boolean truncated) {

    /**
     * One header occurrence, in the order it appeared. A list rather than a map because order is
     * part of the meaning — {@code Received} lines are a delivery path read bottom-up, and
     * collapsing them into a set would destroy it.
     */
    public record ParsedHeader(String name, String value) {
    }

    /**
     * One non-body part: a conventional attachment, or an inline image referenced from the HTML
     * body by {@code contentId}.
     *
     * <p>{@code partPath} is the dotted position of the part within the MIME tree ({@code "0"} is
     * the message itself, {@code "0.2.1"} the first child of its second child). It is how the bytes
     * are found again in {@code rawSource} at download time — {@link MailParser#partAt} walks the
     * same path the walk that produced it took, so the two cannot disagree about which part is
     * which.
     *
     * @param sizeBytes decoded length when the raw source was stored; for a truncated message, the
     *                  encoded size the IMAP structure reported, which is roughly a third larger
     *                  for base64 parts.
     */
    public record ParsedAttachment(
            String partPath,
            String filename,
            String mimeType,
            long sizeBytes,
            String contentId,
            boolean inline) {
    }
}

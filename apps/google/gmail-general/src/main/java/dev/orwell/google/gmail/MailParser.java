package dev.orwell.google.gmail;

import dev.orwell.google.gmail.ParsedMail.ParsedAttachment;
import dev.orwell.google.gmail.ParsedMail.ParsedHeader;
import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Reads one IMAP message into a {@link ParsedMail}, and finds a part again in a stored raw source.
 *
 * <p>Both directions live here on purpose. An attachment is stored as a <em>path</em> into the MIME
 * tree rather than as bytes, so the walk that assigns the path at ingestion and the walk that
 * resolves it at download time must agree exactly. Keeping them in one class — {@link #collect} and
 * {@link #partAt} descending in the same order — is what makes that true by construction rather
 * than by two implementations happening to match.
 *
 * <p>Under the size cap the message is downloaded once and everything is parsed from those bytes,
 * so the index describes precisely what was stored. Over the cap nothing is downloaded: the
 * structure is read from the IMAP {@code BODYSTRUCTURE} the server already reports, text bodies are
 * fetched as individual sections, and attachments are recorded as metadata with no retrievable
 * bytes. That is the difference {@code truncated} marks.
 */
public final class MailParser {
    private static final Session SESSION = Session.getInstance(new Properties());

    /** Root of the MIME tree. Children are {@code 0.1}, {@code 0.2}, and so on, depth-first. */
    private static final String ROOT_PATH = "0";

    private MailParser() {
    }

    /**
     * @param maxBytes largest message to download in full. A larger one is still stored — headers,
     *                 text bodies and attachment metadata — but without its raw source, because a
     *                 handful of 25 MB messages would otherwise dominate the shared database.
     */
    static ParsedMail parse(Message live, long uid, long maxBytes)
            throws MessagingException, IOException {
        long reportedSize = reportedSize(live);
        byte[] raw = null;
        boolean truncated = false;

        if (reportedSize >= 0 && reportedSize > maxBytes) {
            // The server already told us it is too big; never ask for the body at all.
            truncated = true;
        } else {
            raw = readRaw(live);
            if (raw.length > maxBytes) {
                // Only reachable when the server reported no size: we had to fetch to find out.
                raw = null;
                truncated = true;
            }
        }

        // Envelope fields come from the live message even when a re-parsed copy is available:
        // getReceivedDate() is the IMAP INTERNALDATE, which the raw bytes do not carry.
        String id = firstHeader(live, "Message-ID");
        if (id == null || id.isBlank()) {
            id = "uid-" + uid;
        }
        String subject = live.getSubject() == null ? "" : live.getSubject();
        String from = addresses(live.getFrom());
        String to = addresses(live.getRecipients(Message.RecipientType.TO));
        var received = live.getReceivedDate() != null ? live.getReceivedDate() : live.getSentDate();
        long receivedAt = received != null ? received.getTime() : System.currentTimeMillis();

        Part source = raw != null ? reparse(raw) : live;
        Collector collected = new Collector(raw != null, maxBytes);
        collected.collect(source, ROOT_PATH);

        return new ParsedMail(id.trim(), subject, from, to, receivedAt,
                collected.text == null ? "" : collected.text,
                collected.html == null ? "" : collected.html,
                headersOf(source), List.copyOf(collected.attachments),
                raw, reportedSize >= 0 ? reportedSize : (raw == null ? 0L : raw.length), truncated);
    }

    /**
     * Resolves {@code partPath} against a stored raw source.
     *
     * @throws MessagingException if the path does not address a part of this message, which means
     *                            the stored index and the stored bytes have diverged.
     */
    public static Part partAt(byte[] rawSource, String partPath)
            throws MessagingException, IOException {
        Part part = reparse(rawSource);
        String[] steps = partPath.split("\\.");
        if (steps.length == 0 || !ROOT_PATH.equals(steps[0])) {
            throw new MessagingException("Not a part path of this message: " + partPath);
        }
        for (int step = 1; step < steps.length; step++) {
            int index;
            try {
                index = Integer.parseInt(steps[step]);
            } catch (NumberFormatException exception) {
                throw new MessagingException("Not a part path of this message: " + partPath);
            }
            Object content = part.getContent();
            if (!(content instanceof Multipart multipart) || index < 1 || index > multipart.getCount()) {
                throw new MessagingException("Part path does not exist in this message: " + partPath);
            }
            part = multipart.getBodyPart(index - 1);
        }
        return part;
    }

    /** Every header, in the order the message carries them, duplicates included. */
    private static List<ParsedHeader> headersOf(Part part) throws MessagingException {
        List<ParsedHeader> headers = new ArrayList<>();
        Enumeration<Header> all = part.getAllHeaders();
        while (all.hasMoreElements()) {
            Header header = all.nextElement();
            headers.add(new ParsedHeader(header.getName(),
                    header.getValue() == null ? "" : header.getValue()));
        }
        return List.copyOf(headers);
    }

    /** Walks the MIME tree once, splitting it into the two body candidates and everything else. */
    private static final class Collector {
        private final boolean haveRawSource;
        private final long maxBytes;
        private final List<ParsedAttachment> attachments = new ArrayList<>();
        private String text;
        private String html;

        private Collector(boolean haveRawSource, long maxBytes) {
            this.haveRawSource = haveRawSource;
            this.maxBytes = maxBytes;
        }

        private void collect(Part part, String path) throws MessagingException, IOException {
            if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    collect(multipart.getBodyPart(i), path + "." + (i + 1));
                }
                return;
            }

            String filename = fileName(part);
            String disposition = disposition(part);
            boolean attached = Part.ATTACHMENT.equalsIgnoreCase(disposition) || filename != null;

            // A text part that is not presented as a file is body, not an attachment. Only the
            // first of each type is taken: the rest of a multipart/alternative set are renderings
            // of the same content, and calling them attachments would invent files that the sender
            // never attached. Nothing is lost by ignoring them — they remain in the raw source.
            if (!attached) {
                if (text == null && part.isMimeType("text/plain")) {
                    text = textOf(part);
                    return;
                }
                if (html == null && part.isMimeType("text/html")) {
                    html = textOf(part);
                    return;
                }
            }

            String contentId = unbracket(firstHeader(part, "Content-ID"));
            boolean inline = Part.INLINE.equalsIgnoreCase(disposition)
                    || (disposition == null && contentId != null);
            attachments.add(new ParsedAttachment(path, filename, mimeType(part),
                    sizeOf(part), contentId, inline));
        }

        /**
         * Decoded length when the bytes are local, and the structure's encoded size otherwise —
         * measuring a remote part exactly would mean downloading the very body the cap exists to
         * avoid.
         */
        private long sizeOf(Part part) {
            if (!haveRawSource) {
                return Math.max(structuralSize(part), 0);
            }
            try (InputStream stream = part.getInputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    total += read;
                }
                return total;
            } catch (Exception exception) {
                return Math.max(structuralSize(part), 0);
            }
        }

        private String textOf(Part part) throws MessagingException, IOException {
            // A text body larger than the whole cap is pathological; reading it would defeat the
            // cap on the truncated path, where this part is still a network fetch.
            if (!haveRawSource && structuralSize(part) > maxBytes) {
                return "";
            }
            Object content = part.getContent();
            if (content == null) {
                return "";
            }
            if (content instanceof InputStream stream) {
                try (stream) {
                    return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return content.toString();
        }
    }

    private static int structuralSize(Part part) {
        try {
            return part.getSize();
        } catch (MessagingException exception) {
            return -1;
        }
    }

    private static long reportedSize(Message message) {
        try {
            // IMAP answers this from RFC822.SIZE without fetching the body; other stores may not.
            return message.getSize();
        } catch (MessagingException exception) {
            return -1L;
        }
    }

    private static byte[] readRaw(Message message) throws MessagingException, IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        message.writeTo(buffer);
        return buffer.toByteArray();
    }

    private static MimeMessage reparse(byte[] raw) throws MessagingException {
        return new MimeMessage(SESSION, new ByteArrayInputStream(raw));
    }

    /** The base type, lowercased and without parameters; {@code application/octet-stream} if unparseable. */
    private static String mimeType(Part part) {
        try {
            String raw = part.getContentType();
            if (raw == null || raw.isBlank()) {
                return "application/octet-stream";
            }
            return new ContentType(raw).getBaseType().toLowerCase(Locale.ROOT);
        } catch (MessagingException exception) {
            return "application/octet-stream";
        }
    }

    /** Null rather than an exception: a malformed encoded filename must not lose the attachment. */
    private static String fileName(Part part) {
        try {
            String name = part.getFileName();
            return (name == null || name.isBlank()) ? null : name;
        } catch (MessagingException exception) {
            return null;
        }
    }

    private static String disposition(Part part) {
        try {
            return part.getDisposition();
        } catch (MessagingException exception) {
            return null;
        }
    }

    private static String firstHeader(Part part, String name) {
        try {
            String[] values = part.getHeader(name);
            return (values != null && values.length > 0) ? values[0] : null;
        } catch (MessagingException exception) {
            return null;
        }
    }

    private static String unbracket(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() > 1) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        return Arrays.stream(addresses).map(Address::toString).collect(Collectors.joining(", "));
    }
}

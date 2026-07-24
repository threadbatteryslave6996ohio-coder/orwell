package dev.orwell.google.gmail;

import dev.orwell.google.gmail.entity.ImapCheckpointEntity;
import dev.orwell.google.gmail.repository.ImapCheckpointRepository;
import dev.orwell.logging.Logger;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Polls the mailbox over IMAP on a fixed interval and hands each new message to
 * {@link GmailService} for storage and webhook fan-out. Unlike the IDLE-based listener this
 * replaces, no connection is held open between polls: each run opens a fresh IMAP connection,
 * fetches whatever arrived since the last checkpoint, and closes it. Progress is tracked by IMAP
 * UID in the {@code imap_checkpoints} table, so mail that arrives between polls (or while the
 * service is down) is picked up on the next run.
 */
@Component
public class ImapMailPoller {
    private final String host;
    private final int port;
    private final boolean ssl;
    private final String username;
    private final String password;
    private final String folderName;
    private final GmailService delivery;
    private final ImapCheckpointRepository checkpoints;
    private final Logger logger;
    // A slow poll (e.g. a large catch-up) could still be running when the next tick fires;
    // this keeps two polls from opening overlapping IMAP connections.
    private final AtomicBoolean polling = new AtomicBoolean(false);

    public ImapMailPoller(
            @Value("${gmail.imap.host}") String host,
            @Value("${gmail.imap.port}") int port,
            @Value("${gmail.imap.ssl}") boolean ssl,
            @Value("${gmail.imap.username}") String username,
            @Value("${gmail.imap.password}") String password,
            @Value("${gmail.imap.folder}") String folderName,
            GmailService delivery,
            ImapCheckpointRepository checkpoints,
            Logger logger) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.username = username;
        this.password = password;
        this.folderName = folderName;
        this.delivery = delivery;
        this.checkpoints = checkpoints;
        this.logger = logger;
    }

    @Scheduled(fixedRateString = "${gmail.poll-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledPoll() {
        if (!polling.compareAndSet(false, true)) {
            logger.warn("Skipping IMAP poll; the previous poll is still running.", Map.of());
            return;
        }
        try {
            poll();
        } catch (Exception exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("IMAP poll failed; will retry next interval.", metadata);
        } finally {
            polling.set(false);
        }
    }

    void poll() throws MessagingException {
        IMAPStore store = null;
        IMAPFolder folder = null;
        try {
            String protocol = ssl ? "imaps" : "imap";
            Properties props = new Properties();
            props.put("mail.store.protocol", protocol);
            props.put("mail." + protocol + ".host", host);
            props.put("mail." + protocol + ".port", String.valueOf(port));
            if (ssl) {
                props.put("mail.imaps.ssl.enable", "true");
                // Verify the server certificate matches the host; guards against a MITM.
                props.put("mail.imaps.ssl.checkserveridentity", "true");
            }
            Session session = Session.getInstance(props);
            store = (IMAPStore) session.getStore(protocol);
            store.connect(host, username, password);
            folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            long uidValidity = folder.getUIDValidity();
            ImapCheckpointEntity checkpoint = loadCheckpoint(folder, uidValidity);
            long maxUid = catchUp(folder, checkpoint.getLastUid());
            if (maxUid > checkpoint.getLastUid()) {
                checkpoint.advance(maxUid, Instant.now());
                checkpoints.save(checkpoint);
            }
        } finally {
            closeQuietly(folder, store);
        }
    }

    private ImapCheckpointEntity loadCheckpoint(IMAPFolder folder, long uidValidity) throws MessagingException {
        var existing = checkpoints.findById(folderName);
        if (existing.isPresent() && existing.get().getUidValidity() == uidValidity) {
            return existing.get();
        }
        if (existing.isPresent()) {
            logger.warn("IMAP UIDVALIDITY changed; resyncing from the current mailbox head.", Map.of(
                    "saved", existing.get().getUidValidity(), "current", uidValidity));
        }
        // No usable checkpoint: start from the newest existing message so we deliver only new mail
        // rather than replaying the whole mailbox.
        long head = currentMaxUid(folder);
        return checkpoints.save(new ImapCheckpointEntity(folderName, uidValidity, head, Instant.now()));
    }

    private static long currentMaxUid(IMAPFolder folder) throws MessagingException {
        int count = folder.getMessageCount();
        if (count <= 0) {
            return 0L;
        }
        return folder.getUID(folder.getMessage(count));
    }

    private long catchUp(IMAPFolder folder, long fromUid) throws MessagingException {
        Message[] messages = folder.getMessagesByUID(fromUid + 1, UIDFolder.LASTUID);
        long max = fromUid;
        if (messages == null) {
            return max;
        }
        for (Message message : messages) {
            if (message == null) {
                continue;
            }
            long uid;
            try {
                uid = folder.getUID(message);
            } catch (MessagingException exception) {
                continue;
            }
            // getMessagesByUID returns the boundary message when the range is empty; skip it.
            if (uid <= fromUid) {
                continue;
            }
            try {
                delivery.deliver(toGmailMessage(message, uid), uid);
            } catch (Exception exception) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("uid", uid);
                metadata.put("error", exception.getMessage());
                logger.error("Failed to process IMAP message.", metadata);
            }
            // Advance past this UID even on failure so one bad message can't wedge the cursor.
            max = Math.max(max, uid);
        }
        return max;
    }

    private static void closeQuietly(IMAPFolder folder, IMAPStore store) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (Exception ignored) {
            // best effort
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Maps a Jakarta Mail message to the storage/webhook DTO. Package-visible for unit testing. */
    static GmailMessage toGmailMessage(Message message, long uid) throws MessagingException, IOException {
        String id = firstHeader(message, "Message-ID");
        if (id == null || id.isBlank()) {
            id = "uid-" + uid;
        }
        String subject = message.getSubject() == null ? "" : message.getSubject();
        String from = addresses(message.getFrom());
        String to = addresses(message.getRecipients(Message.RecipientType.TO));
        var received = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        long receivedAt = received != null ? received.getTime() : System.currentTimeMillis();
        return new GmailMessage(id, "", subject, from, to, receivedAt, extractText(message));
    }

    private static String firstHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    private static String addresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        return Arrays.stream(addresses).map(Address::toString).collect(Collectors.joining(", "));
    }

    /** Returns the first {@code text/plain} part, walking multipart bodies; empty if none. */
    static String extractText(Part part) throws MessagingException, IOException {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : content.toString();
        }
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String text = extractText(multipart.getBodyPart(i));
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }
}

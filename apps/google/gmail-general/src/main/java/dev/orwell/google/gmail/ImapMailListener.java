package dev.orwell.google.gmail;

import dev.orwell.logging.Logger;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.MessageCountAdapter;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Streams new mailbox messages over IMAP and hands each to {@link GmailService} for storage and
 * webhook fan-out. A single background thread keeps one IMAP connection open, uses IMAP IDLE to be
 * notified of new mail in near real time, and reconnects on failure. Progress is tracked by IMAP
 * UID in a {@code .imap-uid} checkpoint next to the message store, so mail that arrives while the
 * listener is down is picked up on the next connect.
 */
@Component
public class ImapMailListener implements SmartLifecycle {
    private static final long RECONNECT_DELAY_MS = 10_000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String folderName;
    private final Path checkpointFile;
    private final GmailService delivery;
    private final Logger logger;

    private volatile boolean running;
    private Thread worker;
    private volatile IMAPStore store;
    private volatile IMAPFolder folder;
    // Touched only by the worker thread (the IDLE callback runs inline on it), so no locking needed.
    private long uidValidity;
    private long lastUid;

    public ImapMailListener(
            @Value("${gmail.imap.host}") String host,
            @Value("${gmail.imap.port}") int port,
            @Value("${gmail.imap.username}") String username,
            @Value("${gmail.imap.password}") String password,
            @Value("${gmail.imap.folder}") String folderName,
            @Value("${gmail.store-dir}") String storeDir,
            GmailService delivery,
            Logger logger) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.folderName = folderName;
        this.checkpointFile = Path.of(storeDir).resolve(".imap-uid");
        this.delivery = delivery;
        this.logger = logger;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "imap-mail-listener");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        closeQuietly();
        if (worker != null) {
            worker.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void runLoop() {
        while (running) {
            try {
                connect();
                uidValidity = folder.getUIDValidity();
                lastUid = loadCheckpoint(uidValidity);
                lastUid = catchUp(lastUid);
                saveCheckpoint(uidValidity, lastUid);
                logger.info("IMAP listener connected.", Map.of(
                        "folder", folderName, "uidValidity", uidValidity, "lastUid", lastUid));
                idleLoop();
            } catch (Exception exception) {
                if (!running) {
                    break;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("error", exception.getMessage());
                logger.warn("IMAP listener disconnected; will reconnect.", metadata);
            } finally {
                closeQuietly();
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        logger.info("IMAP listener stopped.", Map.of());
    }

    private void connect() throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        Session session = Session.getInstance(props);
        IMAPStore openedStore = (IMAPStore) session.getStore("imaps");
        openedStore.connect(host, username, password);
        IMAPFolder openedFolder = (IMAPFolder) openedStore.getFolder(folderName);
        openedFolder.open(Folder.READ_ONLY);
        this.store = openedStore;
        this.folder = openedFolder;
    }

    // Blocks in IDLE until the server reports activity, then fetches whatever arrived by UID. Doing
    // the fetch after idle() returns (rather than inside the message-count callback) keeps IMAP
    // commands off the IDLE protocol. A normal idle() return with no new mail (Gmail auto-ends IDLE
    // roughly every 29 minutes) simply re-arms it, which doubles as a keep-alive.
    private void idleLoop() throws MessagingException {
        folder.addMessageCountListener(new MessageCountAdapter() {
        });
        while (running && folder.isOpen()) {
            folder.idle();
            long caught = catchUp(lastUid);
            if (caught > lastUid) {
                lastUid = caught;
                saveCheckpoint(uidValidity, lastUid);
            }
        }
    }

    private long catchUp(long fromUid) throws MessagingException {
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
                delivery.deliver(toGmailMessage(message, uid));
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

    private long loadCheckpoint(long currentUidValidity) throws IOException, MessagingException {
        if (Files.exists(checkpointFile)) {
            String[] parts = Files.readString(checkpointFile).trim().split(":");
            if (parts.length == 2) {
                long savedValidity = Long.parseLong(parts[0]);
                long savedUid = Long.parseLong(parts[1]);
                if (savedValidity == currentUidValidity) {
                    return savedUid;
                }
                logger.warn("IMAP UIDVALIDITY changed; resyncing from the current mailbox head.",
                        Map.of("saved", savedValidity, "current", currentUidValidity));
            }
        }
        // No usable checkpoint: start from the newest existing message so we deliver only new mail
        // rather than replaying the whole mailbox.
        return currentMaxUid();
    }

    private long currentMaxUid() throws MessagingException {
        int count = folder.getMessageCount();
        if (count <= 0) {
            return 0L;
        }
        return folder.getUID(folder.getMessage(count));
    }

    private void saveCheckpoint(long currentUidValidity, long uid) {
        try {
            Files.writeString(checkpointFile, currentUidValidity + ":" + uid);
        } catch (IOException exception) {
            logger.warn("Could not persist IMAP checkpoint.", Map.of(
                    "error", String.valueOf(exception.getMessage())));
        }
    }

    private void closeQuietly() {
        IMAPFolder openFolder = this.folder;
        IMAPStore openStore = this.store;
        this.folder = null;
        this.store = null;
        try {
            if (openFolder != null && openFolder.isOpen()) {
                openFolder.close(false);
            }
        } catch (Exception ignored) {
            // best effort
        }
        try {
            if (openStore != null && openStore.isConnected()) {
                openStore.close();
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** Maps a Jakarta Mail message to the webhook DTO. Package-visible for unit testing. */
    static GmailMessage toGmailMessage(Message message, long uid) throws MessagingException, IOException {
        String id = firstHeader(message, "Message-ID");
        if (id == null || id.isBlank()) {
            id = "uid-" + uid;
        }
        String subject = message.getSubject() == null ? "" : message.getSubject();
        String from = addresses(message.getFrom());
        String to = addresses(message.getRecipients(Message.RecipientType.TO));
        Date received = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
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

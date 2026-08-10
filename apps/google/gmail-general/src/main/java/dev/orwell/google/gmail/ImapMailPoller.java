package dev.orwell.google.gmail;

import dev.orwell.google.gmail.entity.ImapCheckpointEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.UserSecretEntity;
import dev.orwell.google.gmail.repository.ImapCheckpointRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.UserSecretRepository;
import dev.orwell.logging.Logger;
import jakarta.annotation.PreDestroy;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Polls every configured mailbox over IMAP on a fixed interval and hands each new message to
 * {@link GmailService} for storage and webhook fan-out. No connection is held open between polls:
 * each run opens a fresh IMAP connection per user, fetches whatever arrived since that user's last
 * checkpoint, and closes it.
 *
 * <p>Mailboxes come from the {@code users} table, and each user's IMAP password from the
 * one-to-one {@code secrets} row — there is no mailbox in configuration. The host, port, TLS
 * setting and folder remain global: only the account differs per user. A user with no secret row
 * is skipped with a warning rather than treated as an error, because that is the normal state
 * between creating a user and setting its password.
 *
 * <p>Mailboxes are polled <em>concurrently</em>, on a bounded pool of
 * {@code GMAIL_POLL_CONCURRENCY} threads, and each mailbox carries its own in-flight flag. That
 * combination is what keeps one slow or hanging mailbox from delaying the rest: a single shared
 * flag would make every other mailbox wait for the slow one's round to finish, and IMAP polling is
 * network-bound, so the threads are almost always idle rather than busy.
 */
@Component
public class ImapMailPoller {
    private final String host;
    private final int port;
    private final boolean ssl;
    private final String folderName;
    private final GmailService delivery;
    private final UserRepository users;
    private final UserSecretRepository secrets;
    private final ImapCheckpointRepository checkpoints;
    private final Logger logger;
    private final ExecutorService pollers;
    // One flag per user id, so a mailbox still being polled when the next tick fires is skipped on
    // its own rather than blocking the round. Entries for deleted users are dropped each round.
    private final ConcurrentMap<Long, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    public ImapMailPoller(
            @Value("${gmail.imap.host}") String host,
            @Value("${gmail.imap.port}") int port,
            @Value("${gmail.imap.ssl}") boolean ssl,
            @Value("${gmail.imap.folder}") String folderName,
            @Value("${gmail.poll-concurrency}") int pollConcurrency,
            GmailService delivery,
            UserRepository users,
            UserSecretRepository secrets,
            ImapCheckpointRepository checkpoints,
            Logger logger) {
        this.host = host;
        this.port = port;
        this.ssl = ssl;
        this.folderName = folderName;
        this.delivery = delivery;
        this.users = users;
        this.secrets = secrets;
        this.checkpoints = checkpoints;
        this.logger = logger;
        this.pollers = Executors.newFixedThreadPool(Math.max(1, pollConcurrency), runnable -> {
            Thread thread = new Thread(runnable, "imap-poller");
            // Daemon so a hung IMAP read cannot keep the JVM alive after the context closes.
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedRateString = "${gmail.poll-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledPoll() {
        try {
            pollAllUsers();
        } catch (Exception exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("IMAP poll round failed; will retry next interval.", metadata);
        }
    }

    /** Stops accepting work at shutdown; in-flight polls are daemon threads and are not awaited. */
    @PreDestroy
    void stopPolling() {
        pollers.shutdownNow();
    }

    /**
     * Submits one poll per user. One user's mailbox being unreachable — wrong password, IMAP
     * rejecting the login, a network failure — must not stop the others from being polled, so each
     * user's failure is contained and reported rather than ending the round.
     *
     * <p>This method only dispatches; it does not wait for the polls to finish. A mailbox whose
     * previous poll is still running is skipped for this round and retried on the next tick, which
     * is why the flag is released by the worker rather than here.
     */
    void pollAllUsers() {
        List<UserEntity> all = users.findAll();
        if (all.isEmpty()) {
            logger.warn("No users configured; nothing to poll.", Map.of());
            return;
        }
        // Users can be deleted; without this the flag map would grow for the process's lifetime.
        inFlight.keySet().retainAll(all.stream().map(UserEntity::getId).collect(Collectors.toSet()));
        for (UserEntity user : all) {
            AtomicBoolean flag =
                    inFlight.computeIfAbsent(user.getId(), id -> new AtomicBoolean(false));
            if (!flag.compareAndSet(false, true)) {
                logger.warn("Skipping mailbox; its previous poll is still running.", Map.of(
                        "userId", user.getId(), "email", user.getEmail()));
                continue;
            }
            try {
                pollers.execute(() -> {
                    try {
                        pollUser(user);
                    } catch (Exception exception) {
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("userId", user.getId());
                        metadata.put("email", user.getEmail());
                        metadata.put("error", exception.getMessage());
                        logger.error("IMAP poll failed for user; continuing with the rest.", metadata);
                    } finally {
                        flag.set(false);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                // Shutdown raced with a tick: release the flag so a later round can retry.
                flag.set(false);
            }
        }
    }

    void pollUser(UserEntity user) throws MessagingException {
        Optional<UserSecretEntity> secret = secrets.findByUserId(user.getId());
        if (secret.isEmpty()) {
            logger.warn("Skipping user with no IMAP secret configured.", Map.of(
                    "userId", user.getId(), "email", user.getEmail()));
            return;
        }

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
            store.connect(host, user.getEmail(), secret.get().getImapPassword());
            folder = (IMAPFolder) store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);

            long uidValidity = folder.getUIDValidity();
            ImapCheckpointEntity checkpoint = loadCheckpoint(user, folder, uidValidity);
            long maxUid = catchUp(user, folder, checkpoint.getLastUid());
            if (maxUid > checkpoint.getLastUid()) {
                checkpoint.advance(maxUid, Instant.now());
                checkpoints.save(checkpoint);
            }
        } finally {
            closeQuietly(folder, store);
        }
    }

    private ImapCheckpointEntity loadCheckpoint(UserEntity user, IMAPFolder folder, long uidValidity)
            throws MessagingException {
        var existing = checkpoints.findByUserIdAndFolder(user.getId(), folderName);
        if (existing.isPresent() && existing.get().getUidValidity() == uidValidity) {
            return existing.get();
        }
        // No usable checkpoint: start from the newest existing message so we deliver only new mail
        // rather than replaying the whole mailbox.
        long head = currentMaxUid(folder);
        if (existing.isPresent()) {
            logger.warn("IMAP UIDVALIDITY changed; resyncing from the current mailbox head.", Map.of(
                    "userId", user.getId(),
                    "saved", existing.get().getUidValidity(),
                    "current", uidValidity));
            ImapCheckpointEntity checkpoint = existing.get();
            checkpoint.resync(uidValidity, head, Instant.now());
            return checkpoints.save(checkpoint);
        }
        return checkpoints.save(new ImapCheckpointEntity(user, folderName, uidValidity, head, Instant.now()));
    }

    private static long currentMaxUid(IMAPFolder folder) throws MessagingException {
        int count = folder.getMessageCount();
        if (count <= 0) {
            return 0L;
        }
        return folder.getUID(folder.getMessage(count));
    }

    private long catchUp(UserEntity user, IMAPFolder folder, long fromUid) throws MessagingException {
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
                delivery.deliver(user, toGmailMessage(user.getEmail(), message, uid), uid);
            } catch (Exception exception) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("userId", user.getId());
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

    /**
     * Maps a Jakarta Mail message to the storage/webhook DTO. Package-visible for unit testing.
     *
     * <p>{@code account} comes from the user being polled rather than from any header: the
     * {@code To} address is not it — a mail can reach a mailbox by Bcc, alias, or forwarding, and
     * that address is what a subscriber would otherwise have to guess the owner from.
     */
    static GmailMessage toGmailMessage(String account, Message message, long uid)
            throws MessagingException, IOException {
        String id = firstHeader(message, "Message-ID");
        if (id == null || id.isBlank()) {
            id = "uid-" + uid;
        }
        String subject = message.getSubject() == null ? "" : message.getSubject();
        String from = addresses(message.getFrom());
        String to = addresses(message.getRecipients(Message.RecipientType.TO));
        var received = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
        long receivedAt = received != null ? received.getTime() : System.currentTimeMillis();
        return new GmailMessage(id, account, subject, from, to, receivedAt, extractText(message));
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

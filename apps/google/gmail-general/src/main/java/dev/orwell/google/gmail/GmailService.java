package dev.orwell.google.gmail;

import dev.orwell.google.gmail.ParsedMail.ParsedAttachment;
import dev.orwell.google.gmail.ParsedMail.ParsedHeader;
import dev.orwell.google.gmail.entity.EmailAttachmentEntity;
import dev.orwell.google.gmail.entity.EmailHeaderEntity;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.EmailRawSourceEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailAttachmentRepository;
import dev.orwell.google.gmail.repository.EmailHeaderRepository;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.EmailRawSourceRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persists each received mailbox message to the {@code gmail} database. The ingestion side (reading
 * the mailbox) lives in {@link ImapMailPoller}; this service owns storage.
 *
 * <p>Storage spans four tables — the message, its headers, its attachment index, and its raw
 * source — written in one transaction. Partially storing a message would be worse than not storing
 * it: the dedup check is on the message row, so a message whose attachment rows failed to commit
 * would look complete forever afterwards and never be re-fetched.
 *
 * <p>Delivery to per-mailbox subscribers is <em>not</em> done here — it is driven from a cursor per
 * subscription by {@link WebhookDeliveryJob}, so a receiver that was down catches up instead of
 * losing mail. The only thing this class forwards is the legacy {@code GMAIL_WEBHOOK_CLIENTS}
 * broadcast, which has no cursor and stays best-effort: one attempt, and a failure is logged and
 * dropped. That difference is a further reason to migrate off it.
 */
@Service
public class GmailService {
    private final EmailMessageRepository repository;
    private final EmailHeaderRepository headers;
    private final EmailAttachmentRepository attachments;
    private final EmailRawSourceRepository rawSources;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookSender sender;
    private final MailPayloads payloads;
    private final TransactionTemplate transactions;
    private final List<String> broadcastClients;

    public GmailService(
            @Value("${gmail.webhook-clients}") String webhookClients,
            EmailMessageRepository repository,
            EmailHeaderRepository headers,
            EmailAttachmentRepository attachments,
            EmailRawSourceRepository rawSources,
            WebhookSubscriptionRepository subscriptions,
            WebhookSender sender,
            MailPayloads payloads,
            PlatformTransactionManager transactionManager,
            Logger logger
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.rawSources = Objects.requireNonNull(rawSources, "rawSources");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        // An explicit template rather than @Transactional on deliver(): the broadcast below makes
        // HTTP calls, and a self-invoked @Transactional method would either not apply at all or
        // hold a database connection open for the length of a webhook round trip.
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.broadcastClients = Arrays.stream(webhookClients.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        if (!this.broadcastClients.isEmpty()) {
            logger.warn("GMAIL_WEBHOOK_CLIENTS is set: these URLs receive every user's mail, not "
                            + "just one mailbox's, and delivery to them is best-effort with no "
                            + "retry. Migrate them to per-mailbox subscriptions "
                            + "(POST /subscriptions) and unset it.",
                    Map.of("clientCount", this.broadcastClients.size()));
        }
    }

    /**
     * Saves the message and everything hanging off it against {@code user}, unless that user
     * already has it. The unique constraint on {@code (user_id, message_id)} is the dedup key, so a
     * redelivered message (e.g. re-fetched after a checkpoint resync) is stored once per user — two
     * users who both received the same mail each keep a copy.
     *
     * <p>Storing is what makes a message deliverable: {@link WebhookDeliveryJob} walks stored rows
     * by id, so a subscriber receives everything committed here, in order, exactly once unless a
     * retry duplicates it.
     */
    public void deliver(UserEntity user, ParsedMail mail, long imapUid) {
        if (repository.existsByUserIdAndMessageId(user.getId(), mail.messageId())) {
            return;
        }
        GmailMessage payload = transactions.execute(status -> store(user, mail, imapUid));
        broadcast(user, payload);
    }

    private GmailMessage store(UserEntity user, ParsedMail mail, long imapUid) {
        EmailMessageEntity stored = repository.save(new EmailMessageEntity(
                user, mail.messageId(), imapUid, mail.subject(), mail.from(), mail.to(),
                Instant.ofEpochMilli(mail.receivedAt()), mail.bodyText(), mail.bodyHtml(),
                mail.rawSizeBytes(), mail.truncated(), Instant.now()));

        List<EmailHeaderEntity> headerRows = new ArrayList<>(mail.headers().size());
        int ordinal = 0;
        for (ParsedHeader header : mail.headers()) {
            headerRows.add(new EmailHeaderEntity(stored, ordinal++, header.name(), header.value()));
        }
        headers.saveAll(headerRows);

        List<EmailAttachmentEntity> attachmentRows = new ArrayList<>(mail.attachments().size());
        int partIndex = 0;
        for (ParsedAttachment attachment : mail.attachments()) {
            attachmentRows.add(new EmailAttachmentEntity(stored, partIndex++, attachment.partPath(),
                    attachment.filename(), attachment.mimeType(), attachment.sizeBytes(),
                    attachment.contentId(), attachment.inline()));
        }
        attachments.saveAll(attachmentRows);

        if (mail.rawSource() != null) {
            rawSources.save(new EmailRawSourceEntity(stored, mail.rawSource()));
        }
        return payloads.payload(stored, user.getEmail(), headerRows, attachmentRows);
    }

    /**
     * Legacy fan-out: every mailbox to every configured URL, one attempt, failures dropped.
     *
     * <p>A URL that this user has also subscribed is skipped here, so it receives the message once
     * — through the cursor-tracked path, which is the durable one. That is what makes migrating off
     * the broadcast list safe to do gradually: subscribe the URL first, and this side goes quiet
     * for it without a window of double delivery.
     */
    private void broadcast(UserEntity user, GmailMessage message) {
        if (broadcastClients.isEmpty() || message == null) {
            return;
        }
        Set<String> subscribed = subscriptions.findByUserIdAndActiveTrueOrderByIdAsc(user.getId())
                .stream().map(WebhookSubscriptionEntity::getUrl).collect(Collectors.toSet());
        for (String client : broadcastClients) {
            if (!subscribed.contains(client)) {
                sender.send(client, message);
            }
        }
    }
}

package dev.orwell.google.gmail;

import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
 * <p>Delivery to per-mailbox subscribers is <em>not</em> done here — it is driven from a cursor per
 * subscription by {@link WebhookDeliveryJob}, so a receiver that was down catches up instead of
 * losing mail. The only thing this class forwards is the legacy {@code GMAIL_WEBHOOK_CLIENTS}
 * broadcast, which has no cursor and stays best-effort: one attempt, and a failure is logged and
 * dropped. That difference is a further reason to migrate off it.
 */
@Service
public class GmailService {
    private final EmailMessageRepository repository;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookSender sender;
    private final List<String> broadcastClients;

    public GmailService(
            @Value("${gmail.webhook-clients}") String webhookClients,
            EmailMessageRepository repository,
            WebhookSubscriptionRepository subscriptions,
            WebhookSender sender,
            Logger logger
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.sender = Objects.requireNonNull(sender, "sender");
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
     * Saves the message row against {@code user}, unless that user already has it. The unique
     * constraint on {@code (user_id, message_id)} is the dedup key, so a redelivered message (e.g.
     * re-fetched after a checkpoint resync) is stored once per user — two users who both received
     * the same mail each keep a copy.
     *
     * <p>Storing is what makes a message deliverable: {@link WebhookDeliveryJob} walks stored rows
     * by id, so a subscriber receives everything committed here, in order, exactly once unless a
     * retry duplicates it.
     */
    public void deliver(UserEntity user, GmailMessage message, long imapUid) {
        if (repository.existsByUserIdAndMessageId(user.getId(), message.id())) {
            return;
        }
        repository.save(new EmailMessageEntity(
                user, message.id(), imapUid, message.subject(),
                message.from(), message.to(), Instant.ofEpochMilli(message.receivedAt()),
                message.body(), Instant.now()));
        broadcast(user, message);
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
        if (broadcastClients.isEmpty()) {
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

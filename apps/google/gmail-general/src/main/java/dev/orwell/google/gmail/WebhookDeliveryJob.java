package dev.orwell.google.gmail;

import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers stored mail to per-mailbox webhook subscribers, tracking a cursor per subscription.
 *
 * <p>Each active subscription carries a {@code lastDeliveredId}; a round fetches the stored
 * messages of that subscription's mailbox with a greater id, oldest first, and posts them in order.
 * The cursor advances only after a 2xx, so a receiver that was down, slow, or returning errors
 * catches up on a later round rather than losing the mail it missed — the failure mode the previous
 * fire-and-forget fan-out had, where dedup on {@code (user_id, message_id)} meant a message was
 * never re-sent.
 *
 * <p>Delivery is therefore <em>at least once</em>, not exactly once: a receiver that processes a
 * message and then fails to return 2xx (or the connection drops after it replied) will be sent it
 * again. Subscribers should key on {@code id}, which is stable per message and per mailbox.
 *
 * <p>A failing subscription blocks only itself. Delivery stops at the first failure for that one
 * subscription — otherwise a receiver would see later mail before the message it rejected — and
 * every other subscription continues in the same round.
 */
@Component
public class WebhookDeliveryJob {
    /**
     * How many messages one subscription may catch up on per round. Bounded so a subscriber that
     * has been down for a long time cannot monopolise a round, and so one HTTP failure is never
     * more than this many wasted attempts.
     */
    private static final int BATCH = 100;

    private final WebhookSubscriptionRepository subscriptions;
    private final EmailMessageRepository mails;
    private final UserRepository users;
    private final WebhookSender sender;
    private final MailPayloads payloads;
    private final Logger logger;
    // Rounds must not overlap: two rounds walking the same cursor would double-deliver.
    private final AtomicBoolean delivering = new AtomicBoolean(false);

    public WebhookDeliveryJob(
            WebhookSubscriptionRepository subscriptions,
            EmailMessageRepository mails,
            UserRepository users,
            WebhookSender sender,
            MailPayloads payloads,
            Logger logger) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.mails = Objects.requireNonNull(mails, "mails");
        this.users = Objects.requireNonNull(users, "users");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Scheduled(fixedRateString = "${gmail.delivery-interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledDelivery() {
        if (!delivering.compareAndSet(false, true)) {
            return;
        }
        try {
            deliverPending();
        } catch (Exception exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("Webhook delivery round failed; will retry next interval.", metadata);
        } finally {
            delivering.set(false);
        }
    }

    /** One pass over every active subscription. Package-visible so tests can drive a round. */
    void deliverPending() {
        for (WebhookSubscriptionEntity subscription : subscriptions.findByActiveTrueOrderByIdAsc()) {
            try {
                deliverFor(subscription);
            } catch (Exception exception) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("subscriptionId", subscription.getId());
                metadata.put("client", subscription.getUrl());
                metadata.put("error", exception.getMessage());
                logger.error("Webhook delivery failed for a subscription; continuing with the rest.",
                        metadata);
            }
        }
    }

    private void deliverFor(WebhookSubscriptionEntity subscription) {
        // getId() on the lazy proxy is the foreign key and needs no query; getEmail() would.
        Long userId = subscription.getUser().getId();
        Optional<UserEntity> owner = users.findById(userId);
        if (owner.isEmpty()) {
            return;
        }
        String account = owner.get().getEmail();
        List<EmailMessageEntity> pending = mails.findByUserIdAndIdGreaterThanOrderByIdAsc(
                userId, subscription.getLastDeliveredId(), PageRequest.of(0, BATCH));
        // Headers and attachment indexes for the whole batch in one query each, rather than per
        // message: a batch of 100 would otherwise cost 200 extra round trips before the first POST.
        List<GmailMessage> batch = payloads.payloads(pending, account);
        for (int i = 0; i < pending.size(); i++) {
            if (!sender.send(subscription.getUrl(), batch.get(i))) {
                // Leave the cursor where it is: this message is retried next round, and the
                // messages after it stay behind it so the subscriber never sees them out of order.
                return;
            }
            subscription.advanceTo(pending.get(i).getId());
            subscriptions.save(subscription);
        }
    }
}

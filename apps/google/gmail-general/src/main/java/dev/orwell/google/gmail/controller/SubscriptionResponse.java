package dev.orwell.google.gmail.controller;

import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;

import java.time.Instant;

/**
 * @param account         the mailbox this subscription drains, echoed back so a caller managing
 *                        several consumer identities can tell which mailbox it just subscribed to
 * @param lastDeliveredId the delivery cursor — the highest mail {@code id} this receiver has
 *                        acknowledged. Exposed so a subscriber can see how far behind it is
 *                        without querying the database.
 */
public record SubscriptionResponse(Long id, String account, String url, boolean active,
                                   long lastDeliveredId, Instant createdAt) {
    public static SubscriptionResponse from(WebhookSubscriptionEntity entity) {
        return new SubscriptionResponse(entity.getId(), entity.getUser().getEmail(),
                entity.getUrl(), entity.isActive(), entity.getLastDeliveredId(),
                entity.getCreatedAt());
    }
}

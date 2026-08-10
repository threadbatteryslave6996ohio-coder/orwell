package dev.orwell.google.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A URL that receives one user's mail as it arrives.
 *
 * <p>A subscription belongs to the {@link UserEntity} whose mailbox it drains, and delivery is
 * scoped to that user: {@link dev.orwell.google.gmail.GmailService} looks up the subscriptions of
 * the mailbox a message was polled from, so a subscriber never sees another user's mail. There is
 * deliberately no column naming the subscribing client separately — the owning user already carries
 * the consumer's {@code clientId}, and a second identity here could disagree with it.
 *
 * <p>{@code url} is unique per user rather than globally: two users may legitimately point at the
 * same receiver, and each should get their own row (and their own {@code active} flag).
 *
 * <p>{@code active} exists so a subscription can be paused without losing the row. Nothing sets it
 * to false today — the delete route removes the row outright — but a delivery job that disables a
 * persistently failing endpoint is the obvious next use, and adding the column later would mean
 * another hand-written migration.
 *
 * <p>{@code lastDeliveredId} is the delivery cursor: the highest {@code email_messages.id} this
 * subscriber has acknowledged with a 2xx. {@link dev.orwell.google.gmail.WebhookDeliveryJob} walks
 * forward from it, so a receiver that was down catches up on its next round instead of losing the
 * mail it missed. It starts at the mailbox's current head rather than at zero, so subscribing does
 * not replay the whole history — the same rule a newly registered mailbox follows.
 */
@Entity
@Table(
        name = "webhook_subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_subscriptions_user_url", columnNames = {"user_id", "url"}),
        indexes = @Index(name = "idx_webhook_subscriptions_user_id", columnList = "user_id"))
public class WebhookSubscriptionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_webhook_subscriptions_user"))
    private UserEntity user;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_delivered_id", nullable = false)
    private long lastDeliveredId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookSubscriptionEntity() {
    }

    public WebhookSubscriptionEntity(UserEntity user, String url, long lastDeliveredId,
            Instant createdAt) {
        this.user = user;
        this.url = url;
        this.active = true;
        this.lastDeliveredId = lastDeliveredId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getUrl() {
        return url;
    }

    public boolean isActive() {
        return active;
    }

    public long getLastDeliveredId() {
        return lastDeliveredId;
    }

    /**
     * Records that everything up to and including {@code messageId} has been accepted. Never moves
     * backwards: a stale in-memory copy must not un-deliver mail the cursor has already passed.
     */
    public void advanceTo(long messageId) {
        if (messageId > this.lastDeliveredId) {
            this.lastDeliveredId = messageId;
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package dev.orwell.bucket.detection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A URL that receives frames as they arrive at the bastion.
 *
 * <p>A subscription belongs to the {@code clientId} that created it, taken from the authenticated
 * caller and never from the request body, so a consumer can only manage its own rows. Unlike
 * gmail's per-mailbox equivalent there is no owning user table: frames are produced by cameras,
 * not by accounts, and the only identity that matters here is the consumer's.
 *
 * <p>{@code source} scopes the subscription to one camera; null means every source. It is part of
 * the row rather than a delivery-time filter so an unscoped and a scoped subscription to the same
 * URL keep independent cursors.
 *
 * <p>{@code lastDeliveredId} is the delivery cursor: the highest {@code frame_events.id} this
 * subscriber acknowledged with a 2xx. It starts at the current head so subscribing does not
 * replay whatever is still inside the retention window.
 */
@Entity
@Table(
        name = "frame_subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_frame_subscriptions_client_url_source",
                columnNames = {"client_id", "url", "source"}),
        indexes = @Index(name = "idx_frame_subscriptions_client_id", columnList = "client_id"))
public class FrameSubscriptionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 255)
    private String clientId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    /** Null means "every source". */
    @Column(name = "source", length = 255)
    private String source;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_delivered_id", nullable = false)
    private long lastDeliveredId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FrameSubscriptionEntity() {
    }

    public FrameSubscriptionEntity(String clientId, String url, String source, long lastDeliveredId,
            Instant createdAt) {
        this.clientId = clientId;
        this.url = url;
        this.source = source;
        this.active = true;
        this.lastDeliveredId = lastDeliveredId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getUrl() {
        return url;
    }

    public String getSource() {
        return source;
    }

    public boolean isActive() {
        return active;
    }

    public long getLastDeliveredId() {
        return lastDeliveredId;
    }

    /**
     * Records that everything up to and including {@code frameId} has been accepted. Never moves
     * backwards: a stale in-memory copy must not un-deliver frames the cursor has already passed.
     */
    public void advanceTo(long frameId) {
        if (frameId > this.lastDeliveredId) {
            this.lastDeliveredId = frameId;
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

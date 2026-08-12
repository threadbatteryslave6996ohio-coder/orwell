package dev.orwell.bucket.hub.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * How far a named subscription has been streamed.
 *
 * <p>A client that connects with {@code ?subscription=<name>} gets its position remembered: on
 * reconnect the hub replays the stored frames it missed and only then goes live. Without a name a
 * connection is ephemeral — it starts at the head and nothing is written here.
 *
 * <p>{@code name} is unique on its own rather than per source. A name is the client's chosen
 * identity, and one identity has one position; reusing a name with a different {@code source}
 * filter continues the same cursor rather than starting a second one. {@code source} is recorded
 * for operators reading the table, not used as a key.
 *
 * <p>The cursor is written periodically rather than per frame — at 5 fps a row write per frame
 * would cost more than the streaming does. That makes redelivery after a crash possible: a client
 * may see frames it already got, so it should key on the SSE event id, which is the
 * {@code frame_events.id}.
 */
@Entity
@Table(
        name = "frame_cursors",
        uniqueConstraints = @UniqueConstraint(name = "uq_frame_cursors_name", columnNames = "name"))
public class FrameCursorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Informational: the filter the name last connected with. Null means every source. */
    @Column(name = "source", length = 255)
    private String source;

    @Column(name = "last_delivered_id", nullable = false)
    private long lastDeliveredId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FrameCursorEntity() {
    }

    public FrameCursorEntity(String name, String source, long lastDeliveredId, Instant createdAt) {
        this.name = name;
        this.source = source;
        this.lastDeliveredId = lastDeliveredId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public long getLastDeliveredId() {
        return lastDeliveredId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Records that everything up to and including {@code frameId} has been streamed. Never moves
     * backwards: two connections sharing a name must not un-deliver what the other has passed.
     */
    public void advanceTo(long frameId, Instant now) {
        if (frameId > this.lastDeliveredId) {
            this.lastDeliveredId = frameId;
            this.updatedAt = now;
        }
    }

    public void rememberSource(String source) {
        this.source = source;
    }
}

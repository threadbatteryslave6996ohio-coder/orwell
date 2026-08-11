package dev.orwell.bucket.detection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One frame accepted by the hub: relayed live to whoever is connected, and kept here so a client
 * that reconnects can be replayed what it missed.
 *
 * <p>The row id is also the SSE event id, which is what makes catch-up work: a client resumes from
 * its {@code Last-Event-ID} (or its stored {@link FrameCursorEntity}), and the hub reads forward
 * from here before switching it to the live stream.
 *
 * <p>The bytes live here as {@code bytea} rather than in an object store, which makes this table
 * the loudest sizing decision in the service — a 40 KB frame at 5 fps is ~200 KB/s <em>per
 * source</em>. Two things keep it bounded: {@code DETECTION_RELAY_MODE=changed} keeps most frames
 * from being written at all, and {@code DETECTION_FRAME_RETENTION_SECONDS} caps how long any of
 * them survive.
 *
 * <p>Retention wins over catch-up on purpose: {@link
 * dev.orwell.bucket.detection.FrameRetentionJob} deletes aged rows whether or not every client has
 * read them. A client away for longer than the window resumes at the oldest surviving frame rather
 * than the table growing without limit. If that trade is wrong for a deployment, the window is the
 * knob — an unbounded log is not on offer.
 */
@Entity
@Table(
        name = "frame_events",
        indexes = {
                @Index(name = "idx_frame_events_source_id", columnList = "source, id"),
                @Index(name = "idx_frame_events_captured_at", columnList = "captured_at")
        })
public class FrameEventEntity implements Persistable<Long> {
    /**
     * Assigned by {@link dev.orwell.bucket.detection.FrameIdAllocator} before the row is written,
     * not generated on insert — the hub needs the id to broadcast, which happens first.
     */
    @Id
    private Long id;

    @Column(name = "source", nullable = false, length = 255)
    private String source;

    /** The producer's own frame counter; optional, so nullable and never used as identity. */
    @Column(name = "frame_index")
    private Long frameIndex;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "changed", nullable = false)
    private boolean changed;

    @Column(name = "changed_fraction", nullable = false)
    private double changedFraction;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    // Plain byte[] maps to Postgres bytea. Deliberately not @Lob, which would put the frame in a
    // large object and make retention deletes leave orphans behind.
    @Column(name = "frame_bytes", nullable = false)
    private byte[] frameBytes;

    /**
     * Spring Data decides insert-vs-update from the id, and this id is always set — so without
     * {@link Persistable} every save would issue a pointless SELECT before its INSERT.
     */
    @Transient
    private boolean isNew = true;

    protected FrameEventEntity() {
        this.isNew = false;
    }

    public FrameEventEntity(Long id, String source, Long frameIndex, String sha256, boolean changed,
            double changedFraction, Instant capturedAt, byte[] frameBytes) {
        this.id = id;
        this.source = source;
        this.frameIndex = frameIndex;
        this.sha256 = sha256;
        this.changed = changed;
        this.changedFraction = changedFraction;
        this.capturedAt = capturedAt;
        this.frameBytes = frameBytes;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getSource() {
        return source;
    }

    public Long getFrameIndex() {
        return frameIndex;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isChanged() {
        return changed;
    }

    public double getChangedFraction() {
        return changedFraction;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public byte[] getFrameBytes() {
        return frameBytes;
    }
}

package dev.orwell.bucket.detection.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One frame accepted by the bastion, held until it has been fanned out or aged past retention.
 *
 * <p>The bytes live here as {@code bytea} rather than in an object store because delivery is
 * cursor-tracked: a subscriber that was down has to be re-sent the frames it missed, which means
 * they must still exist. That makes this table the loudest sizing decision in the service — a
 * 40 KB frame at 5 fps is ~200 KB/s <em>per source</em>, so
 * {@code DETECTION_FRAME_RETENTION_SECONDS} is what keeps it bounded, and
 * {@code DETECTION_FANOUT_MODE=changed} is what keeps most frames from being written at all.
 *
 * <p>Retention wins over delivery on purpose: {@link
 * dev.orwell.bucket.detection.FrameRetentionJob} deletes aged rows whether or not every subscriber
 * received them. A subscriber down for longer than the window loses those frames permanently
 * rather than the table growing without limit. If that trade is wrong for a deployment, the
 * window is the knob — an unbounded queue is not on offer.
 */
@Entity
@Table(
        name = "frame_events",
        indexes = {
                @Index(name = "idx_frame_events_source_id", columnList = "source, id"),
                @Index(name = "idx_frame_events_captured_at", columnList = "captured_at")
        })
public class FrameEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    protected FrameEventEntity() {
    }

    public FrameEventEntity(String source, Long frameIndex, String sha256, boolean changed,
            double changedFraction, Instant capturedAt, byte[] frameBytes) {
        this.source = source;
        this.frameIndex = frameIndex;
        this.sha256 = sha256;
        this.changed = changed;
        this.changedFraction = changedFraction;
        this.capturedAt = capturedAt;
        this.frameBytes = frameBytes;
    }

    public Long getId() {
        return id;
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

package dev.orwell.google.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Poller progress for one IMAP folder of one {@link UserEntity}.
 *
 * <p>The row is keyed by {@code (user_id, folder)}, not by folder alone. Every mailbox has an
 * {@code INBOX}, and UIDs are only meaningful within a single account — one row per folder name
 * would have two users overwriting each other's cursor, so each would skip whatever the other had
 * already consumed.
 *
 * <p>{@code uidValidity} pins the row to a specific mailbox generation: if the server ever
 * reassigns it, previously-recorded UIDs are meaningless and the poller resyncs from the mailbox
 * head.
 */
@Entity
@Table(
        name = "imap_checkpoints",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_imap_checkpoints_user_folder", columnNames = {"user_id", "folder"}))
public class ImapCheckpointEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_imap_checkpoints_user"))
    private UserEntity user;

    @Column(name = "folder", nullable = false, length = 255)
    private String folder;

    @Column(name = "uid_validity", nullable = false)
    private long uidValidity;

    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImapCheckpointEntity() {
    }

    public ImapCheckpointEntity(
            UserEntity user, String folder, long uidValidity, long lastUid, Instant updatedAt) {
        this.user = user;
        this.folder = folder;
        this.uidValidity = uidValidity;
        this.lastUid = lastUid;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getFolder() {
        return folder;
    }

    public long getUidValidity() {
        return uidValidity;
    }

    public long getLastUid() {
        return lastUid;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void advance(long lastUid, Instant updatedAt) {
        this.lastUid = lastUid;
        this.updatedAt = updatedAt;
    }

    public void resync(long uidValidity, long lastUid, Instant updatedAt) {
        this.uidValidity = uidValidity;
        this.lastUid = lastUid;
        this.updatedAt = updatedAt;
    }
}

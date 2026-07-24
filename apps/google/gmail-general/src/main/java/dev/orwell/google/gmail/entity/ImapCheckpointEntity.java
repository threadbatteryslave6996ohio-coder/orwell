package dev.orwell.google.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Poller progress for one IMAP folder, replacing the old {@code .imap-uid} checkpoint file.
 * {@code uidValidity} pins the row to a specific mailbox generation: if the server ever reassigns
 * it, previously-recorded UIDs are meaningless and the poller resyncs from the mailbox head.
 */
@Entity
@Table(name = "imap_checkpoints")
public class ImapCheckpointEntity {
    @Id
    @Column(name = "folder", length = 255)
    private String folder;

    @Column(name = "uid_validity", nullable = false)
    private long uidValidity;

    @Column(name = "last_uid", nullable = false)
    private long lastUid;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImapCheckpointEntity() {
    }

    public ImapCheckpointEntity(String folder, long uidValidity, long lastUid, Instant updatedAt) {
        this.folder = folder;
        this.uidValidity = uidValidity;
        this.lastUid = lastUid;
        this.updatedAt = updatedAt;
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
}

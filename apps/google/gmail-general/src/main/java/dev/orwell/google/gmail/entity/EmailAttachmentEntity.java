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

/**
 * One non-body part of a stored message — an attached file, or an image the HTML body references
 * inline by {@code contentId}.
 *
 * <p>This row holds no bytes. The content lives once, in {@code email_messages.raw_source}, and
 * {@code partPath} is the address that finds it there; storing the decoded bytes here as well would
 * double what every message with an attachment costs to keep. The consequence is that a message
 * stored with {@code truncated = true} has these rows but no retrievable content — its raw source
 * was never downloaded.
 *
 * <p>{@code partIndex} is the stable 0-based number the download URL uses. It is the enumeration
 * order of the MIME walk, so it stays put for a given stored message; {@code partPath} is the
 * structural address and is what actually resolves the bytes.
 */
@Entity
@Table(
        name = "email_attachments",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_email_attachments_message_part", columnNames = {"message_id", "part_index"}),
        indexes = @Index(name = "idx_email_attachments_message_id", columnList = "message_id"))
public class EmailAttachmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_email_attachments_message"))
    private EmailMessageEntity message;

    @Column(name = "part_index", nullable = false)
    private int partIndex;

    @Column(name = "part_path", nullable = false, length = 128)
    private String partPath;

    /** Null when the sender attached a part without a filename, which inline images often are. */
    @Column(name = "filename", columnDefinition = "text")
    private String filename;

    @Column(name = "mime_type", nullable = false, length = 255)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_id", length = 998)
    private String contentId;

    @Column(name = "inline", nullable = false)
    private boolean inline;

    protected EmailAttachmentEntity() {
    }

    public EmailAttachmentEntity(EmailMessageEntity message, int partIndex, String partPath,
            String filename, String mimeType, long sizeBytes, String contentId, boolean inline) {
        this.message = message;
        this.partIndex = partIndex;
        this.partPath = partPath;
        this.filename = filename;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.contentId = contentId;
        this.inline = inline;
    }

    public Long getId() {
        return id;
    }

    public EmailMessageEntity getMessage() {
        return message;
    }

    public int getPartIndex() {
        return partIndex;
    }

    public String getPartPath() {
        return partPath;
    }

    public String getFilename() {
        return filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentId() {
        return contentId;
    }

    public boolean isInline() {
        return inline;
    }
}

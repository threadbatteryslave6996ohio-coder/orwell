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
 * A stored mailbox message, owned by the {@link UserEntity} whose mailbox it came from.
 *
 * <p>{@code id} is an auto-increment surrogate key assigned in insertion (i.e. consumption) order,
 * which doubles as the cursor consumers pass back as {@code ?checkpoint=} to resume where they
 * left off — it is not derived from anything IMAP or Gmail expose. Because ids are handed out
 * across all users from one sequence, a single user's ids are increasing but not contiguous; that
 * is fine for a cursor, which only ever asks for "greater than".
 *
 * <p>{@code messageId} (the RFC 822 {@code Message-ID} header, or a {@code uid-<uid>} fallback
 * when a message lacks one) is the dedup key. It is unique <em>per user</em> rather than globally:
 * two users who are both recipients of the same mail each receive their own copy, and a global
 * constraint would silently drop the second one.
 */
@Entity
@Table(
        name = "email_messages",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_email_messages_user_message", columnNames = {"user_id", "message_id"}),
        indexes = {
                @Index(name = "idx_email_messages_received_at", columnList = "received_at"),
                @Index(name = "idx_email_messages_user_id", columnList = "user_id")
        })
public class EmailMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_email_messages_user"))
    private UserEntity user;

    @Column(name = "message_id", nullable = false, length = 998)
    private String messageId;

    @Column(name = "imap_uid", nullable = false)
    private long imapUid;

    @Column(name = "subject", nullable = false, columnDefinition = "text")
    private String subject;

    @Column(name = "from_address", nullable = false, columnDefinition = "text")
    private String fromAddress;

    @Column(name = "to_address", nullable = false, columnDefinition = "text")
    private String toAddress;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmailMessageEntity() {
    }

    public EmailMessageEntity(UserEntity user, String messageId, long imapUid, String subject,
            String fromAddress, String toAddress, Instant receivedAt, String body, Instant createdAt) {
        this.user = user;
        this.messageId = messageId;
        this.imapUid = imapUid;
        this.subject = subject;
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.receivedAt = receivedAt;
        this.body = body;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getMessageId() {
        return messageId;
    }

    public long getImapUid() {
        return imapUid;
    }

    public String getSubject() {
        return subject;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getToAddress() {
        return toAddress;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

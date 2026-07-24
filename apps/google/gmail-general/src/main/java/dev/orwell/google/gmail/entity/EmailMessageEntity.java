package dev.orwell.google.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A stored mailbox message. {@code id} is an auto-increment surrogate key assigned in insertion
 * (i.e. consumption) order, which doubles as the cursor consumers pass back as
 * {@code ?checkpoint=} to resume where they left off — it is not derived from anything IMAP or
 * Gmail expose. {@code messageId} (the RFC 822 {@code Message-ID} header, or a
 * {@code uid-<uid>} fallback when a message lacks one) is the dedup key the poller checks before
 * inserting.
 */
@Entity
@Table(name = "email_messages", indexes = {
        @Index(name = "idx_email_messages_received_at", columnList = "received_at")
})
public class EmailMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 998)
    private String messageId;

    @Column(name = "imap_uid", nullable = false)
    private long imapUid;

    @Column(name = "thread_id", nullable = false, length = 255)
    private String threadId;

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

    public EmailMessageEntity(String messageId, long imapUid, String threadId, String subject,
            String fromAddress, String toAddress, Instant receivedAt, String body, Instant createdAt) {
        this.messageId = messageId;
        this.imapUid = imapUid;
        this.threadId = threadId;
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

    public String getMessageId() {
        return messageId;
    }

    public long getImapUid() {
        return imapUid;
    }

    public String getThreadId() {
        return threadId;
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

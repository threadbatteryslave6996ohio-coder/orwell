package dev.orwell.google.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * The complete RFC 822 source of one stored message, byte for byte as the server sent it.
 *
 * <p>This is what makes "nothing is lost" more than a claim about the parser: the parsed body,
 * headers and attachment index are all derived from these bytes, so a part the parser mishandles is
 * still here to be re-read. Attachment downloads resolve their content out of this row rather than
 * storing a second copy of every attachment.
 *
 * <p>A table of its own rather than a column on {@link EmailMessageEntity} because it is by far the
 * largest thing stored per message. A {@code bytea} column on the message row would be loaded by
 * every query that returns a message — {@code GET /mails?limit=500} would pull up to 500 whole
 * messages into memory to render a list of subjects. Here it is read only when it is asked for.
 * ({@code @Basic(fetch = LAZY)} would express the same intent but only takes effect under bytecode
 * enhancement, which this build does not run, so it would silently do nothing.)
 *
 * <p>Deliberately not {@code @Lob}: on Postgres that maps to a large-object OID stored outside the
 * table, with its own lifecycle to manage and no content in a plain row-level dump.
 *
 * <p>A message stored above {@code GMAIL_MAX_MESSAGE_BYTES} has no row here at all — that is what
 * {@code EmailMessageEntity.truncated} records.
 */
@Entity
@Table(name = "email_raw_sources")
public class EmailRawSourceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_email_raw_sources_message"))
    private EmailMessageEntity message;

    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    protected EmailRawSourceEntity() {
    }

    public EmailRawSourceEntity(EmailMessageEntity message, byte[] content) {
        this.message = message;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public EmailMessageEntity getMessage() {
        return message;
    }

    public byte[] getContent() {
        return content;
    }
}

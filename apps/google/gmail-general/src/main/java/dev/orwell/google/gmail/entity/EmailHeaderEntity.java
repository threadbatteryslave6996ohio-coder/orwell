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

/**
 * One header occurrence of one stored message.
 *
 * <p>A row per occurrence rather than a map column, because header names repeat and their order
 * carries meaning: the {@code Received} chain is the delivery path, read bottom-up, and a map would
 * keep only the last hop. {@code ordinal} preserves the order the message carried, so the original
 * header block can be reconstructed from these rows alone.
 *
 * <p>Deliberately not mapped as a collection on {@link EmailMessageEntity}: reads happen outside a
 * transaction (the controller serialises after the repository call returns), where a lazy
 * collection would throw and an eager one would issue a query per message on every page.
 * {@code EmailHeaderRepository} batch-loads a whole page in one query instead.
 */
@Entity
@Table(
        name = "email_headers",
        indexes = @Index(name = "idx_email_headers_message_id", columnList = "message_id"))
public class EmailHeaderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_email_headers_message"))
    private EmailMessageEntity message;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    protected EmailHeaderEntity() {
    }

    public EmailHeaderEntity(EmailMessageEntity message, int ordinal, String name, String value) {
        this.message = message;
        this.ordinal = ordinal;
        this.name = name;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public EmailMessageEntity getMessage() {
        return message;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }
}

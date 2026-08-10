package dev.orwell.google.gmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A mailbox this service polls, and the consumer allowed to read its mail.
 *
 * <p>{@code email} is the IMAP login for the mailbox — the poller connects as this address. The
 * IMAP host, port, TLS setting and folder stay global configuration; only the account differs per
 * user.
 *
 * <p>{@code clientId} is the auth-server client id of the consumer that owns this mailbox. Every
 * read in {@link dev.orwell.google.gmail.controller.MailController} resolves the user from the
 * authenticated caller's client id, so a consumer can only ever reach its own mail: there is no
 * request parameter that selects a user.
 */
@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "client_id", nullable = false, unique = true, length = 255)
    private String clientId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserEntity() {
    }

    public UserEntity(String email, String clientId, Instant createdAt) {
        this.email = email;
        this.clientId = clientId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getClientId() {
        return clientId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

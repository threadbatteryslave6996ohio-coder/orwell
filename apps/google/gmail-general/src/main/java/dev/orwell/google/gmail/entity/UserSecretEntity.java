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

import java.time.Instant;

/**
 * The IMAP credential for one {@link UserEntity}, one row per user — {@code user_id} carries a
 * unique constraint, so a user cannot accumulate several.
 *
 * <p>{@code imapPassword} is stored as written, in plaintext. Anyone with read access to this
 * database, a backup, or a replica therefore holds every user's live mailbox password. That is a
 * deliberate choice of the current design rather than an oversight; encrypting the column (or
 * holding only a reference into the secrets-manager service) is the upgrade path if the threat
 * model changes.
 */
@Entity
@Table(name = "secrets")
public class UserSecretEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_secrets_user"))
    private UserEntity user;

    @Column(name = "imap_password", nullable = false, columnDefinition = "text")
    private String imapPassword;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserSecretEntity() {
    }

    public UserSecretEntity(UserEntity user, String imapPassword, Instant updatedAt) {
        this.user = user;
        this.imapPassword = imapPassword;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public UserEntity getUser() {
        return user;
    }

    public String getImapPassword() {
        return imapPassword;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String imapPassword, Instant updatedAt) {
        this.imapPassword = imapPassword;
        this.updatedAt = updatedAt;
    }
}

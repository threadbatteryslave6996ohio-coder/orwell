package dev.orwell.auth.http.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One issued login token, stored as a hash.
 *
 * <p>The index is not decoration: {@code TokenCleanupJob} asks, for each candidate row, whether a
 * newer token exists for the same identity, and Postgres does not index a foreign key on its own —
 * without it that question is a sequential scan per candidate.
 */
@Entity
@Table(
        name = "client_tokens",
        indexes = @Index(name = "idx_client_tokens_identity_created", columnList = "client_identity_id, created_at"))
public class ClientToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_identity_id", nullable = false)
    private ClientIdentity identity;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When this token last authenticated something. Starts at {@link #createdAt} rather than null,
     * so "idle since" is one column the cleanup job can compare without a {@code COALESCE} — a
     * token nobody has used yet is idle from the moment it was issued.
     *
     * <p>Written by {@code TokenUsage}, and deliberately not on every check: see the throttle
     * there. It is therefore a lower bound on the last use, never an upper one.
     */
    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    protected ClientToken() {
    }

    public ClientToken(ClientIdentity identity, String tokenHash, Instant createdAt) {
        this.identity = identity;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.lastUsedAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public ClientIdentity getIdentity() {
        return identity;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}

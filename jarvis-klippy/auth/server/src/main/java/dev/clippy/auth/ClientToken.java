package dev.clippy.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "client_tokens")
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

    protected ClientToken() {
    }

    public ClientToken(ClientIdentity identity, String tokenHash, Instant createdAt) {
        this.identity = identity;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
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
}

package dev.orwell.auth.http.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One client credential, owned by a workspace.
 *
 * <p>{@code clientId} is unique <em>within</em> a workspace rather than globally, which is the
 * whole point of the column: two teams can both have a {@code backup} identity without agreeing on
 * a naming scheme first. The composite constraint is what enforces it — see the migration note in
 * this module's README before pointing a server at a database created before workspaces existed,
 * because the old single-column unique index on {@code client_id} still forbids the second one.
 */
@Entity
@Table(
        name = "client_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_client_identities_workspace_client_id",
                columnNames = {"workspace", "client_id"}))
public class ClientIdentity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workspace", nullable = false, length = 64)
    private String workspace;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Column(name = "secret_hash", nullable = false, length = 512)
    private String secretHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ClientIdentity() {
    }

    public ClientIdentity(String workspace, String clientId, String secretHash, Instant createdAt) {
        this.workspace = workspace;
        this.clientId = clientId;
        this.secretHash = secretHash;
        this.createdAt = createdAt;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getWorkspace() {
        return workspace;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return active;
    }
}

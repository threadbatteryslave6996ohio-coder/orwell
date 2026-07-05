package dev.orwell.secrets.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "secret_bundle_entries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_bundle_env", columnNames = {"bundle_id", "env_id"})
})
public class SecretBundleEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false)
    private SecretBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "env_id", nullable = false)
    private SecretEnvironment environment;

    protected SecretBundleEntry() {
    }

    public SecretBundleEntry(SecretBundle bundle, SecretEnvironment environment) {
        this.bundle = bundle;
        this.environment = environment;
    }

    public Long getId() {
        return id;
    }

    public SecretBundle getBundle() {
        return bundle;
    }

    public SecretEnvironment getEnvironment() {
        return environment;
    }
}

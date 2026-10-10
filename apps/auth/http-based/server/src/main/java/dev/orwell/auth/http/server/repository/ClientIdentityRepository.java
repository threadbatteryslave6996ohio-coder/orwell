package dev.orwell.auth.http.server.repository;

import dev.orwell.auth.http.server.entity.ClientIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientIdentityRepository extends JpaRepository<ClientIdentity, Long> {
    boolean existsByWorkspaceAndClientId(String workspace, String clientId);

    /**
     * The workspace is part of every lookup, not a filter applied afterwards: a {@code clientId}
     * alone no longer names one identity, so a query that omits it would be ambiguous by
     * construction rather than merely unscoped.
     */
    Optional<ClientIdentity> findByWorkspaceAndClientId(String workspace, String clientId);
}

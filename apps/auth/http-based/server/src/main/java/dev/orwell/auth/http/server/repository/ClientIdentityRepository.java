package dev.orwell.auth.http.server.repository;

import dev.orwell.auth.http.server.entity.ClientIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientIdentityRepository extends JpaRepository<ClientIdentity, Long> {
    boolean existsByClientId(String clientId);

    Optional<ClientIdentity> findByClientId(String clientId);
}

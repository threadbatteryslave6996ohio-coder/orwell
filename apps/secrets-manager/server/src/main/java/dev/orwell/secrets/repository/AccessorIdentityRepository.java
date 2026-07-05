package dev.orwell.secrets.repository;

import dev.orwell.secrets.model.AccessorIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccessorIdentityRepository extends JpaRepository<AccessorIdentity, Long> {
    boolean existsByName(String name);

    Optional<AccessorIdentity> findByName(String name);
}

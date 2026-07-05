package dev.orwell.secrets.repository;

import dev.orwell.secrets.model.AdminIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminIdentityRepository extends JpaRepository<AdminIdentity, Long> {
    boolean existsByName(String name);

    Optional<AdminIdentity> findByName(String name);
}

package dev.orwell.secrets.repository;

import dev.orwell.secrets.model.SecretGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SecretGroupRepository extends JpaRepository<SecretGroup, Long> {
    boolean existsByName(String name);

    Optional<SecretGroup> findByName(String name);
}

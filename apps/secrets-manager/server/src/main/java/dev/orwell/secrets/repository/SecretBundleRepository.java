package dev.orwell.secrets.repository;

import dev.orwell.secrets.model.SecretBundle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SecretBundleRepository extends JpaRepository<SecretBundle, Long> {
    boolean existsByName(String name);

    Optional<SecretBundle> findByName(String name);
}

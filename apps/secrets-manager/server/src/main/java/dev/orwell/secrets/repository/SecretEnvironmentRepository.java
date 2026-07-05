package dev.orwell.secrets.repository;

import dev.orwell.secrets.model.SecretEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecretEnvironmentRepository extends JpaRepository<SecretEnvironment, Long> {
    List<SecretEnvironment> findByGroupId(Long groupId);

    Optional<SecretEnvironment> findByGroupIdAndId(Long groupId, Long id);

    Optional<SecretEnvironment> findByGroupIdAndName(Long groupId, String name);

    boolean existsByGroupIdAndName(Long groupId, String name);

    long countByGroupId(Long groupId);
}

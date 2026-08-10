package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.UserSecretEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSecretRepository extends JpaRepository<UserSecretEntity, Long> {
    Optional<UserSecretEntity> findByUserId(Long userId);
}

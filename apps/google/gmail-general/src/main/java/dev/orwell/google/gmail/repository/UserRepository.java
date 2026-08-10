package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByClientId(String clientId);

    boolean existsByEmail(String email);

    boolean existsByClientId(String clientId);
}

package dev.orwell.bucket.hub.repository;

import dev.orwell.bucket.hub.entity.FrameCursorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FrameCursorRepository extends JpaRepository<FrameCursorEntity, Long> {
    Optional<FrameCursorEntity> findByName(String name);
}

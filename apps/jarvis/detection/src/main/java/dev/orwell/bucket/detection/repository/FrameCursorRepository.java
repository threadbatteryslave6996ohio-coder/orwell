package dev.orwell.bucket.detection.repository;

import dev.orwell.bucket.detection.entity.FrameCursorEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FrameCursorRepository extends JpaRepository<FrameCursorEntity, Long> {
    Optional<FrameCursorEntity> findByName(String name);
}

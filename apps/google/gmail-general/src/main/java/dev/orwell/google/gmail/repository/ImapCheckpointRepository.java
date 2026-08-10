package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.ImapCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImapCheckpointRepository extends JpaRepository<ImapCheckpointEntity, Long> {
    Optional<ImapCheckpointEntity> findByUserIdAndFolder(Long userId, String folder);
}

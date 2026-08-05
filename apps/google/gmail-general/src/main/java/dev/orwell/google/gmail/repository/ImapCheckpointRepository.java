package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.ImapCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImapCheckpointRepository extends JpaRepository<ImapCheckpointEntity, String> {
}

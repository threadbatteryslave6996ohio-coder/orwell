package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.EmailAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Batch-loaded per page for the same reason as {@link EmailHeaderRepository}. */
public interface EmailAttachmentRepository extends JpaRepository<EmailAttachmentEntity, Long> {
    List<EmailAttachmentEntity> findByMessageIdInOrderByMessageIdAscPartIndexAsc(
            Collection<Long> messageIds);

    Optional<EmailAttachmentEntity> findByMessageIdAndPartIndex(Long messageId, int partIndex);
}

package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.EmailMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailMessageRepository extends JpaRepository<EmailMessageEntity, Long> {
    boolean existsByMessageId(String messageId);

    Optional<EmailMessageEntity> findTopByOrderByIdDesc();

    Page<EmailMessageEntity> findAllByOrderByIdDesc(Pageable pageable);

    List<EmailMessageEntity> findByIdGreaterThanOrderByIdAsc(Long checkpoint, Pageable pageable);
}

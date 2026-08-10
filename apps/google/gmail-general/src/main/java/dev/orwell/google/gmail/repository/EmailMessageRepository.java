package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.EmailMessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Every query is scoped by {@code userId}. There is deliberately no unscoped read: a missing
 * {@code WHERE user_id = ?} would serve one user's mail to another, so the scope is part of the
 * method signature rather than something a caller has to remember to add.
 */
public interface EmailMessageRepository extends JpaRepository<EmailMessageEntity, Long> {
    boolean existsByUserIdAndMessageId(Long userId, String messageId);

    Optional<EmailMessageEntity> findTopByUserIdOrderByIdDesc(Long userId);

    Page<EmailMessageEntity> findAllByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<EmailMessageEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(
            Long userId, Long checkpoint, Pageable pageable);
}

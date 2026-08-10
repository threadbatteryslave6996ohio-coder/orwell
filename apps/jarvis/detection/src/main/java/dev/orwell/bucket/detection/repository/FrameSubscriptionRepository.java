package dev.orwell.bucket.detection.repository;

import dev.orwell.bucket.detection.entity.FrameSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FrameSubscriptionRepository extends JpaRepository<FrameSubscriptionEntity, Long> {
    /** Every subscription the delivery job should advance this round. */
    List<FrameSubscriptionEntity> findByActiveTrueOrderByIdAsc();

    List<FrameSubscriptionEntity> findByClientIdOrderByIdAsc(String clientId);

    /**
     * Scoped by client on purpose: a delete addressed by id alone would let one consumer remove
     * another's subscription by guessing the id.
     */
    Optional<FrameSubscriptionEntity> findByIdAndClientId(Long id, String clientId);

    boolean existsByClientIdAndUrlAndSource(String clientId, String url, String source);
}

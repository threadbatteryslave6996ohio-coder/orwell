package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscriptionEntity, Long> {
    /** The delivery targets for one mailbox. */
    List<WebhookSubscriptionEntity> findByUserIdAndActiveTrueOrderByIdAsc(Long userId);

    /** Every subscription the delivery job should advance this round. */
    List<WebhookSubscriptionEntity> findByActiveTrueOrderByIdAsc();

    List<WebhookSubscriptionEntity> findByUserIdOrderByIdAsc(Long userId);

    /**
     * Scoped by user on purpose: a delete addressed by id alone would let one consumer remove
     * another's subscription by guessing the id.
     */
    Optional<WebhookSubscriptionEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndUrl(Long userId, String url);
}

package dev.orwell.bucket.hub.repository;

import dev.orwell.bucket.hub.entity.FrameEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FrameEventRepository extends JpaRepository<FrameEventEntity, Long> {
    /** The next batch for an unscoped subscription. */
    List<FrameEventEntity> findByIdGreaterThanOrderByIdAsc(long lastDeliveredId, Pageable page);

    /** The next batch for a subscription scoped to one source. */
    List<FrameEventEntity> findByIdGreaterThanAndSourceOrderByIdAsc(
            long lastDeliveredId, String source, Pageable page);

    /** The current head, used to start a new subscription's cursor without replaying the window. */
    Optional<FrameEventEntity> findTopByOrderByIdDesc();

    long countBySource(String source);

    /**
     * Bulk delete rather than a derived {@code deleteBy}: the derived form loads every matching
     * row — frame bytes included — into the persistence context before deleting it, which is the
     * one thing retention must not do on a table this size.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from FrameEventEntity frame where frame.capturedAt < :cutoff")
    int deleteCapturedBefore(@Param("cutoff") Instant cutoff);
}

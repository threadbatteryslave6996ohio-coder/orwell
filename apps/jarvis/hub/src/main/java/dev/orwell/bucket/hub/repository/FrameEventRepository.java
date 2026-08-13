package dev.orwell.bucket.hub.repository;

import dev.orwell.bucket.hub.entity.FrameEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * One page of the range query: frames captured in {@code [from, to)}, oldest id first, after
     * the {@code after} cursor.
     *
     * <p>Filtered by time but ordered and paged by <em>id</em>, which is not the same thing. Ids
     * are allocated before {@code capturedAt} is stamped, so under concurrent ingest the two orders
     * can disagree by a frame or two — but the filter defines the set and the id cursor enumerates
     * it, so no frame in the window is skipped or returned twice however the two interleave. A
     * timestamp cursor could not promise that: frames sharing a {@code capturedAt} would be
     * duplicated or dropped at the page boundary.
     *
     * <p>Two methods rather than one with a nullable {@code :source}, following the replay queries
     * above — Postgres cannot infer the type of a parameter that only ever appears in
     * {@code :source is null}, and the resulting failure is a runtime one.
     */
    @Query("select frame from FrameEventEntity frame "
            + "where frame.capturedAt >= :from and frame.capturedAt < :to and frame.id > :after "
            + "order by frame.id asc")
    List<FrameEventEntity> findCapturedBetween(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("after") long after,
            Pageable page);

    /** The same page, scoped to one camera. */
    @Query("select frame from FrameEventEntity frame "
            + "where frame.capturedAt >= :from and frame.capturedAt < :to and frame.id > :after "
            + "and frame.source = :source order by frame.id asc")
    List<FrameEventEntity> findCapturedBetweenBySource(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("after") long after,
            @Param("source") String source,
            Pageable page);
}

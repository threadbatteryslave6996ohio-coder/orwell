package dev.clippy.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClipboardEntryRepository extends JpaRepository<ClipboardEntry, Long> {
    Optional<ClipboardEntry> findFirstByClientIdAndTimestampAndContentOrderByIdAsc(
            String clientId,
            Instant timestamp,
            String content
    );

    List<ClipboardEntry> findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(
            String clientId,
            Instant from,
            Instant to
    );

    List<ClipboardEntry> findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(
            String clientId,
            Instant from,
            Instant to,
            Pageable pageable
    );

    @Query("""
            select entry from ClipboardEntry entry
            where entry.clientId = :clientId
              and entry.timestamp between :from and :to
              and (entry.timestamp > :afterTimestamp
                   or (entry.timestamp = :afterTimestamp and entry.id > :afterId))
            order by entry.timestamp asc, entry.id asc
            """)
    List<ClipboardEntry> findTimeframePage(
            @Param("clientId") String clientId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("afterTimestamp") Instant afterTimestamp,
            @Param("afterId") long afterId,
            Pageable pageable
    );
}

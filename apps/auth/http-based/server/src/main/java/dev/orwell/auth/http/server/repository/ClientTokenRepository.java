package dev.orwell.auth.http.server.repository;

import dev.orwell.auth.http.server.entity.ClientToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface ClientTokenRepository extends JpaRepository<ClientToken, Long> {
    /**
     * The only lookup used on the token-check path: the {@code join fetch} is load-bearing, since
     * {@link ClientToken#getIdentity()} is lazy and the controller reads it after the transaction.
     */
    @Query("""
            select token from ClientToken token
            join fetch token.identity
            where token.tokenHash = :tokenHash
            """)
    Optional<ClientToken> findWithIdentityByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * Records a use, as a bulk update rather than a dirty entity: the caller holds the token only
     * to answer one request, and a bulk statement neither needs a managed instance nor flushes
     * anything else it happens to be holding.
     *
     * <p>The {@code <} guard makes concurrent uses monotonic. Two requests for the same token can
     * be in flight with different clock readings, and without it the later writer could move the
     * timestamp backwards — which, on this column, means aging a live token toward deletion.
     */
    @Modifying
    @Transactional
    @Query("""
            update ClientToken token
            set token.lastUsedAt = :usedAt
            where token.id = :id and token.lastUsedAt < :usedAt
            """)
    int markUsed(@Param("id") Long id, @Param("usedAt") Instant usedAt);

    /**
     * Deletes up to {@code batchSize} tokens that have been superseded by a later login for the
     * same identity and have not been used since {@code idleSince}. Returns how many went.
     *
     * <p>Native and Postgres-specific, like the rest of this service's persistence, because the
     * two things that make it safe have no JPQL spelling:
     *
     * <ul>
     *   <li><strong>{@code LIMIT}</strong>, so a first sweep over a backlog that accumulated for
     *       months is many small transactions rather than one enormous one. The caller loops.
     *   <li><strong>The row comparison {@code (created_at, id) > (created_at, id)}</strong>, which
     *       is what stops this deleting a client's only live credential. {@code created_at} alone
     *       is not a total order — two logins can land on the same microsecond — and two rows that
     *       each see the other as "newer" would both qualify. Ordering by {@code (created_at, id)}
     *       leaves exactly one maximum per identity, and that one is never a candidate.
     * </ul>
     */
    @Modifying
    @Transactional
    @Query(value = """
            delete from client_tokens where id in (
                select candidate.id from client_tokens candidate
                where candidate.last_used_at < :idleSince
                  and exists (
                      select 1 from client_tokens newer
                      where newer.client_identity_id = candidate.client_identity_id
                        and (newer.created_at, newer.id) > (candidate.created_at, candidate.id)
                  )
                order by candidate.id
                limit :batchSize
            )
            """, nativeQuery = true)
    int deleteSupersededIdleTokens(@Param("idleSince") Instant idleSince, @Param("batchSize") int batchSize);
}

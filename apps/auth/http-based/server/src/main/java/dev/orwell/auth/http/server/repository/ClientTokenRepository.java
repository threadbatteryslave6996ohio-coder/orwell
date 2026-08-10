package dev.orwell.auth.http.server.repository;

import dev.orwell.auth.http.server.entity.ClientToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

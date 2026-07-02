package dev.clippy.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClientTokenRepository extends JpaRepository<ClientToken, Long> {
    Optional<ClientToken> findByTokenHash(String tokenHash);

    @Query("""
            select token from ClientToken token
            join fetch token.identity
            where token.tokenHash = :tokenHash
            """)
    Optional<ClientToken> findWithIdentityByTokenHash(@Param("tokenHash") String tokenHash);
}

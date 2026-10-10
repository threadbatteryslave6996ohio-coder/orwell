package dev.orwell.auth.http.server;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import dev.orwell.auth.http.server.security.TokenUsage;
import dev.orwell.logging.Logger;
import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the sweep against a real Postgres, because every rule it enforces is expressed in SQL: the
 * correlated "is there a newer one" subquery, the row comparison that breaks a {@code created_at}
 * tie, and the batching.
 *
 * <p>The scheduled bean is switched off so nothing sweeps behind the test's back — {@code
 * fixedRate} fires once at startup — and the job under test is constructed directly with the idle
 * period each case wants.
 */
@SpringBootTest(properties = "orwell.auth.token-cleanup.enabled=false")
class TokenCleanupJobTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Autowired
    private ClientTokenRepository tokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext context;

    private final Logger logger = entry -> {
    };

    @BeforeEach
    void clearTables() {
        jdbc.execute("DELETE FROM client_tokens");
        jdbc.execute("DELETE FROM client_identities");
    }

    /** The switch has to actually switch it off, or "append-only" is not an option anyone has. */
    @Test
    void registersNoSweepAtAllWhenTheCleanupIsDisabled() {
        assertThat(context.getBeansOfType(TokenCleanupJob.class)).isEmpty();
    }

    @Test
    void collectsATokenSupersededByALaterLoginAndIdleForTheWholePeriod() {
        long client = identity("client-a");
        long superseded = token(client, "old-hash", NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofDays(3)));
        long current = token(client, "new-hash", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofMinutes(1)));

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isEqualTo(1);
        assertThat(remainingIds()).containsExactly(current).doesNotContain(superseded);
    }

    /**
     * The property that makes this safe for a client that logs in once and then runs for a year:
     * an identity's newest token is never a candidate, however long it has sat unused.
     */
    @Test
    void keepsTheNewestTokenHoweverIdleItIs() {
        long client = identity("client-a");
        long only = token(client, "only-hash", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(400)));

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isZero();
        assertThat(remainingIds()).containsExactly(only);
    }

    /** Superseded but still authenticating: a second process holding the token its client replaced. */
    @Test
    void keepsASupersededTokenThatIsStillBeingUsed() {
        long client = identity("client-a");
        long stillInUse = token(client, "old-hash", NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofHours(2)));
        long current = token(client, "new-hash", NOW.minus(Duration.ofDays(20)), NOW.minus(Duration.ofMinutes(1)));

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isZero();
        assertThat(remainingIds()).containsExactly(stillInUse, current);
    }

    /**
     * Two logins can land on the same microsecond. Ordering by {@code created_at} alone would make
     * each of these the other's successor and collect both — leaving the client with no credential
     * at all, which is the one outcome this job must never produce.
     */
    @Test
    void leavesOneSurvivorWhenTwoTokensShareACreatedAtTimestamp() {
        long client = identity("client-a");
        Instant sameMoment = NOW.minus(Duration.ofDays(10));
        long first = token(client, "hash-1", sameMoment, sameMoment);
        long second = token(client, "hash-2", sameMoment, sameMoment);

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isEqualTo(1);
        assertThat(remainingIds()).containsExactly(Math.max(first, second));
    }

    @Test
    void leavesEveryOtherIdentityAlone() {
        long busy = identity("client-a");
        token(busy, "busy-old", NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofDays(3)));
        long busyCurrent = token(busy, "busy-new", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(2)));
        long quiet = identity("client-b");
        long quietOnly = token(quiet, "quiet", NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofDays(9)));

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isEqualTo(1);
        assertThat(remainingIds()).containsExactly(busyCurrent, quietOnly);
    }

    /**
     * An idle window at or below the usage throttle offers no protection at all — {@code
     * lastUsedAt} trails a real use by up to that much — so it fails startup rather than quietly
     * collecting tokens that are in use.
     */
    @Test
    void refusesAnIdleWindowShorterThanTheIntervalAtWhichUseIsRecorded() {
        assertThatThrownBy(() -> job(TokenUsage.RECORD_INTERVAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_TOKEN_CLEANUP_IDLE_SECONDS");

        assertThatThrownBy(() -> job(Duration.ofMinutes(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(job(TokenUsage.RECORD_INTERVAL.plusSeconds(1))).isNotNull();
    }

    /** The idle period is the configured one, not a hardcoded day. */
    @Test
    void honoursTheConfiguredIdlePeriod() {
        long client = identity("client-a");
        token(client, "old-hash", NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofHours(3)));
        token(client, "new-hash", NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofMinutes(1)));

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isZero();
        assertThat(job(Duration.ofHours(1)).sweep(NOW)).isEqualTo(1);
    }

    /**
     * More rows than one batch holds, which is the shape of the first sweep after this ships: the
     * loop has to come back for the rest, and it has to stop.
     */
    @Test
    void collectsABacklogLargerThanOneBatch() {
        long client = identity("client-a");
        // The parameters are cast explicitly: left bare, Postgres infers them from the `+ interval`
        // and decides they are intervals themselves.
        jdbc.update("""
                INSERT INTO client_tokens (client_identity_id, token_hash, created_at, last_used_at)
                SELECT ?, 'backlog-' || generation,
                       CAST(? AS timestamptz) + (generation * interval '1 second'),
                       CAST(? AS timestamptz) + (generation * interval '1 second')
                FROM generate_series(1, 1200) AS generation
                """,
                client,
                Timestamp.from(NOW.minus(Duration.ofDays(30))),
                Timestamp.from(NOW.minus(Duration.ofDays(30))));

        assertThat(job(Duration.ofDays(1)).sweep(NOW)).isEqualTo(1199);
        assertThat(remainingIds()).hasSize(1);
    }

    private TokenCleanupJob job(Duration idlePeriod) {
        return new TokenCleanupJob(tokens, logger, idlePeriod.toSeconds());
    }

    private long identity(String clientId) {
        return jdbc.queryForObject("""
                INSERT INTO client_identities (workspace, client_id, secret_hash, created_at, active)
                VALUES (?, ?, 'hash', now(), true) RETURNING id
                """, Long.class, AuthWorkspaces.DEFAULT, clientId);
    }

    private long token(long identityId, String tokenHash, Instant createdAt, Instant lastUsedAt) {
        return jdbc.queryForObject("""
                INSERT INTO client_tokens (client_identity_id, token_hash, created_at, last_used_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, identityId, tokenHash, Timestamp.from(createdAt), Timestamp.from(lastUsedAt));
    }

    private List<Long> remainingIds() {
        return jdbc.queryForList("SELECT id FROM client_tokens ORDER BY id", Long.class);
    }
}

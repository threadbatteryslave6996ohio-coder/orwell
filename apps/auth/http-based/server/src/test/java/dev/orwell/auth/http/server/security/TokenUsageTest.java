package dev.orwell.auth.http.server.security;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenUsageTest {
    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    @Mock
    private ClientTokenRepository tokens;

    @Test
    void recordsAUseOnceTheStoredTimestampHasGoneStale() {
        ClientToken token = tokenLastUsed(NOW.minus(TokenUsage.RECORD_INTERVAL).minusSeconds(1));

        new TokenUsage(tokens).record(token, NOW);

        verify(tokens).markUsed(token.getId(), NOW);
    }

    /**
     * The reason this class exists: {@code /tokens/check} is the busiest path in the stack, and a
     * write per call would put a WAL record on every authenticated request in the repo.
     */
    @Test
    void writesNothingForATokenAlreadyKnownToBeInUse() {
        ClientToken token = tokenLastUsed(NOW.minus(Duration.ofSeconds(30)));

        new TokenUsage(tokens).record(token, NOW);

        verify(tokens, never()).markUsed(any(), eq(NOW));
    }

    /** A token nobody has presented yet is idle from issue, so its first use is always recorded. */
    @Test
    void recordsTheFirstUseOfAFreshlyIssuedTokenThatHasSatUnused() {
        ClientToken token = tokenLastUsed(NOW.minus(Duration.ofDays(2)));

        new TokenUsage(tokens).record(token, NOW);

        verify(tokens).markUsed(token.getId(), NOW);
    }

    private ClientToken tokenLastUsed(Instant lastUsedAt) {
        ClientIdentity identity = new ClientIdentity(
                AuthWorkspaces.DEFAULT, "client-a", "secret-hash", lastUsedAt);
        return new ClientToken(identity, "token-hash", lastUsedAt);
    }
}

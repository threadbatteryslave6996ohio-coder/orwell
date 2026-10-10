package dev.orwell.auth.http.server.security;

import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Records that a token authenticated something, which is what {@code TokenCleanupJob} reads to
 * tell a superseded token still in use from one nobody has presented in a day.
 *
 * <p><strong>Deliberately not a write per check.</strong> {@code /tokens/check} is the busiest
 * path in the stack — every authenticated request to every service ends here — and an unconditional
 * {@code UPDATE} would put a row write and a WAL record on all of it. So a use is recorded only
 * once the stored value has gone stale by {@link #RECORD_INTERVAL}, which bounds the writes for
 * one token at twelve an hour however hard it is used.
 *
 * <p>The cost is that {@code lastUsedAt} lags a real use by up to that interval, so a token can in
 * the worst case be collected having been used {@code idlePeriod - RECORD_INTERVAL} ago rather than
 * a full {@code idlePeriod}. Five minutes against a day is noise; the error is only ever in this
 * direction, since the column is never written ahead of an actual use.
 */
@Component
public class TokenUsage {
    /**
     * How stale a stored timestamp may get before a use is written again.
     *
     * <p>Public because it is a lower bound on any idle window that reads {@code lastUsedAt}: a
     * sweep configured to collect tokens idle for less than this would be reading a timestamp that
     * has not caught up yet, and would delete tokens that are in use. {@code TokenCleanupJob}
     * enforces that at startup.
     */
    public static final Duration RECORD_INTERVAL = Duration.ofMinutes(5);

    private final ClientTokenRepository tokens;

    public TokenUsage(ClientTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Call only where the token actually authenticated the caller. A presented token that failed
     * the workspace or {@code clientId} check has not been used — it has been rejected, and
     * refreshing its timestamp would let anyone holding a dead token keep it out of the sweep.
     */
    public void record(ClientToken token, Instant now) {
        if (token.getLastUsedAt().isAfter(now.minus(RECORD_INTERVAL))) {
            return;
        }
        tokens.markUsed(token.getId(), now);
    }
}

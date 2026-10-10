package dev.orwell.auth.http.server;

import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import dev.orwell.auth.http.server.security.TokenUsage;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Removes login tokens that a later login has superseded and that nobody has used for a while,
 * which is what keeps {@code client_tokens} from growing for the life of the deployment: a row
 * went in on every login and nothing ever took one out.
 *
 * <p><strong>Two conditions, both required.</strong> A token is collected only when the identity
 * it belongs to has logged in again since — so the row is a spent credential, not the one its
 * client is holding — <em>and</em> it has not authenticated anything within the idle period. The
 * newest token of an identity is never a candidate, however old it is, which is what makes this
 * safe for a client that logs in once and then runs for a year. A client that re-authenticated
 * while another process was still using the old token keeps both, until that other process goes
 * quiet for a day.
 *
 * <p>Being superseded is not required to be <em>recent</em>. Collecting only recently-replaced
 * tokens would leave every token replaced before the last run in the table forever, which is the
 * growth this exists to stop.
 *
 * <p>Deletes are batched, each batch its own transaction, for the reason
 * {@code dev.orwell.bucket.retention.RetentionSweeper} spells out: the first run after this ships
 * meets however many tokens the deployment has accumulated, and taking them in one statement is a
 * long transaction, one enormous WAL burst and a vacuum storm afterwards.
 */
@Component
@ConditionalOnProperty(name = "orwell.auth.token-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class TokenCleanupJob {
    /** Rows per transaction. Also the ceiling on what one failed batch can cost. */
    private static final int BATCH = 500;

    /**
     * Stops a sweep that outruns its interval from being joined by the next one. Two concurrent
     * sweeps would not corrupt anything — the delete is idempotent — but they would take the same
     * candidate rows and deadlock against each other for no gain.
     */
    private final AtomicBoolean sweeping = new AtomicBoolean(false);

    private final ClientTokenRepository tokens;
    private final Logger logger;
    private final Duration idlePeriod;

    public TokenCleanupJob(
            ClientTokenRepository tokens,
            Logger logger,
            @Value("${orwell.auth.token-cleanup.idle-seconds:86400}") long idleSeconds
    ) {
        this.tokens = tokens;
        this.logger = logger;
        this.idlePeriod = requireLongerThanTheUsageThrottle(idleSeconds);
    }

    /**
     * An idle window shorter than {@link TokenUsage#RECORD_INTERVAL} would collect tokens that are
     * in use, and fails startup rather than doing so.
     *
     * <p>{@code lastUsedAt} is written at most once per token per {@code RECORD_INTERVAL}, so it
     * trails a real use by up to that much. The guarantee this job offers is therefore "never
     * collects a token used within {@code idlePeriod - RECORD_INTERVAL}" — which is a day minus
     * five minutes at the defaults, and nothing at all once the window drops to the throttle. A
     * server that logged a warning and carried on would look healthy while logging clients out
     * mid-request, so this is fatal like {@code AdminBootstrap}'s unusable secret.
     */
    private static Duration requireLongerThanTheUsageThrottle(long idleSeconds) {
        Duration idlePeriod = Duration.ofSeconds(idleSeconds);
        if (idlePeriod.compareTo(TokenUsage.RECORD_INTERVAL) <= 0) {
            throw new IllegalStateException(
                    "AUTH_TOKEN_CLEANUP_IDLE_SECONDS must exceed %d, the interval at which a token's use is recorded; got %d."
                            .formatted(TokenUsage.RECORD_INTERVAL.toSeconds(), idleSeconds));
        }
        return idlePeriod;
    }

    @Scheduled(
            fixedRateString = "${orwell.auth.token-cleanup.interval-seconds:3600}",
            timeUnit = TimeUnit.SECONDS)
    void scheduledSweep() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            sweep(Instant.now());
        } catch (Exception exception) {
            // A failed sweep is a warning, not a fatal: the table having grown for one more
            // interval is not an outage, and the next round retries from wherever this stopped.
            // Map.of is safe here where much of the repo needs LinkedHashMap: toString() on a
            // throwable is never null, unlike the getMessage() those call sites pass.
            logger.warn("Token cleanup round failed; will retry next interval.",
                    Map.of("error", exception.toString()));
        } finally {
            sweeping.set(false);
        }
    }

    /**
     * One pass, batch by batch, until nothing is left to collect. Returns how many rows went, and
     * logs only when that is not zero — this runs on a timer forever, and a steady stream of
     * "deleted 0" would drown the records that mean something.
     *
     * <p>Package-visible so a test can drive a round against a fixed clock.
     */
    long sweep(Instant now) {
        Instant idleSince = now.minus(idlePeriod);
        long deleted = 0;
        while (true) {
            int batch = tokens.deleteSupersededIdleTokens(idleSince, BATCH);
            deleted += batch;
            if (batch < BATCH) {
                break;
            }
        }

        if (deleted > 0) {
            logger.info("Collected superseded login tokens.",
                    Map.of("deleted", deleted, "idleSince", idleSince));
        }
        return deleted;
    }
}

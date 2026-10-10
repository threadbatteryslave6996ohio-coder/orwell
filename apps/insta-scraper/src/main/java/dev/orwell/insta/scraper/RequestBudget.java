package dev.orwell.insta.scraper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How often this process is willing to talk to Instagram.
 *
 * <p>Two halves, because either alone is insufficient. The <b>interval</b> spaces requests out, so
 * a burst never happens; the <b>hourly cap</b> bounds how many there can be however patiently they
 * are spaced. A scraper with only an interval will happily make 1,800 requests an hour at two
 * seconds apart and get the account flagged for it.
 *
 * <p>The interval is waited out. The cap is <b>not</b> — exceeding it throws, so the endpoint can
 * answer 429 and insta can fail over to a paid actor within the second. Blocking for the rest of
 * the hour instead would hold insta's socket open until it timed out, and a timeout is the one
 * failure insta deliberately does <em>not</em> fail over on.
 *
 * <p>This is the reason the scraper is a service and not a source inside insta. The CLI is
 * short-lived and run from cron, so it cannot remember it made 400 requests forty minutes ago; a
 * process that stays up can.
 */
public final class RequestBudget {
    private final long minIntervalMillis;
    private final int hourlyLimit;
    private final Deque<Instant> recent = new ArrayDeque<>();

    private Instant lastRequest;

    public RequestBudget(long minIntervalMillis, int hourlyLimit) {
        this.minIntervalMillis = Math.max(minIntervalMillis, 0);
        this.hourlyLimit = Math.max(hourlyLimit, 1);
    }

    /**
     * Blocks until the next request is allowed, then records it.
     *
     * @throws ExhaustedException if the hourly cap is spent — the caller should answer 429 rather
     *                            than wait, so the walk moves to another source instead of stalling
     * @throws InterruptedException if the wait is interrupted
     */
    public synchronized void take(Instant now) throws ExhaustedException, InterruptedException {
        evictOlderThanAnHour(now);
        if (recent.size() >= hourlyLimit) {
            throw new ExhaustedException(hourlyLimit, secondsUntilSlot(now));
        }
        if (lastRequest != null) {
            long waited = Duration.between(lastRequest, now).toMillis();
            if (waited < minIntervalMillis) {
                // A plain sleep rather than a scheduler: this class is only ever called from the
                // request thread that is about to make the call, and holding the lock is what makes
                // two concurrent walks queue instead of both firing at once.
                Thread.sleep(minIntervalMillis - waited);
            }
        }
        Instant taken = Instant.now();
        lastRequest = taken;
        recent.addLast(taken);
    }

    /** For {@code /health}: how much of the hour is spent. */
    public synchronized int usedThisHour() {
        evictOlderThanAnHour(Instant.now());
        return recent.size();
    }

    public int hourlyLimit() {
        return hourlyLimit;
    }

    private void evictOlderThanAnHour(Instant now) {
        Instant cutoff = now.minus(Duration.ofHours(1));
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.removeFirst();
        }
    }

    private long secondsUntilSlot(Instant now) {
        Instant oldest = recent.peekFirst();
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(now, oldest.plus(Duration.ofHours(1))).toSeconds());
    }

    /** The hourly cap is spent. Carries the retry hint the endpoint puts in {@code Retry-After}. */
    public static final class ExhaustedException extends Exception {
        private final long retryAfterSeconds;

        ExhaustedException(int limit, long retryAfterSeconds) {
            super("The hourly budget of " + limit + " Instagram requests is spent; "
                    + retryAfterSeconds + "s until the next slot.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}

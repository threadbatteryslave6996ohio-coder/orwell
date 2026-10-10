package dev.orwell.health;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One target's running state, and the decision about when to say something.
 *
 * <p>Two thresholds rather than one, for the same reason a lamp detector has hysteresis: a single
 * failed check is not an outage. A restart, a garbage-collection pause, a container being recreated
 * by {@code docker compose up} — all produce one refused connection, and a monitor that pages on
 * each of them gets muted within a week, at which point it protects nothing.
 *
 * <p>So {@code failuresBeforeDown} consecutive failures are required before a target is called down,
 * and {@code successesBeforeUp} consecutive successes before it is called up again. They are separate
 * numbers because the two mistakes are not equally bad: crying wolf costs trust, while declaring a
 * flapping service recovered costs a second alert. Recovery therefore defaults to 1 and going down to
 * 2 — believe good news quickly, bad news twice.
 *
 * <p>Reports <em>transitions</em>, never states. A target that stays down produces one alert, not one
 * per round; the current state is on {@code /targets} for anyone who wants to poll it. That is what
 * makes this safe to point at a real alerting channel.
 *
 * <p>Written by the checking thread and read by request threads, so both the update and the snapshot
 * are synchronized on this object.
 */
public final class TargetWatch {
    /** Where a target is. {@link #UNKNOWN} until enough checks have agreed. */
    public enum State {
        UNKNOWN, UP, DOWN;

        public String label() {
            return name().toLowerCase();
        }
    }

    /**
     * A transition worth telling somebody about.
     *
     * @param downFor how long the target had been down, on a recovery; null otherwise. Carried on the
     *                change rather than read back off the watch afterwards, because by then the watch
     *                has already moved on — the timestamp it would be computed from is the one this
     *                very transition overwrote.
     */
    public record StateChange(HealthTarget target, State from, State to, ProbeResult result,
            Instant at, Duration downFor) {

        public boolean isOutage() {
            return to == State.DOWN;
        }

        public Map<String, Object> asJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("target", target.name());
            json.put("url", target.url().toString());
            json.put("previousState", from.label());
            json.put("state", to.label());
            json.put("detail", result.detail());
            json.put("httpStatus", result.httpStatus());
            json.put("at", at.toString());
            json.put("downForSeconds", downFor == null ? null : downFor.toSeconds());
            return json;
        }
    }

    private final HealthTarget target;
    private final int failuresBeforeDown;
    private final int successesBeforeUp;

    private State state = State.UNKNOWN;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private ProbeResult last;
    private Instant lastCheckedAt;
    private Instant lastChangeAt;
    private Instant upSince;
    private Instant downSince;
    private long checksTotal;
    private long failuresTotal;
    private long outagesTotal;
    private long slowestMillis;

    public TargetWatch(HealthTarget target, int failuresBeforeDown, int successesBeforeUp) {
        this.target = Objects.requireNonNull(target, "target");
        this.failuresBeforeDown = Math.max(1, failuresBeforeDown);
        this.successesBeforeUp = Math.max(1, successesBeforeUp);
    }

    public HealthTarget target() {
        return target;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isDown() {
        return state == State.DOWN;
    }

    /**
     * Records one check.
     *
     * @return the transition, if this check completed one. Empty on every other round.
     */
    public synchronized Optional<StateChange> record(ProbeResult result, Instant at) {
        checksTotal++;
        last = result;
        lastCheckedAt = at;
        slowestMillis = Math.max(slowestMillis, result.durationMillis());
        if (!result.healthy()) {
            failuresTotal++;
        }

        // Consecutive, so one good check inside a run of bad ones restarts the count rather than
        // pausing it — the same rule the fixture detector uses, for the same reason.
        if (result.healthy()) {
            consecutiveSuccesses++;
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;
            consecutiveSuccesses = 0;
        }

        State candidate = candidate();
        if (candidate == null || candidate == state) {
            return Optional.empty();
        }

        State previous = state;
        state = candidate;
        lastChangeAt = at;
        Duration downFor = null;
        if (candidate == State.DOWN) {
            outagesTotal++;
            upSince = null;
            downSince = at;
        } else {
            // How long it was out, measured before downSince is cleared. This is the number the
            // recovery alert leads with, and the only one anybody asks for afterwards.
            downFor = downSince == null ? null : Duration.between(downSince, at);
            downSince = null;
            upSince = at;
        }
        // The first confirmed check is reported too: on a restart you want to be told what is
        // already broken, not only what breaks next.
        return Optional.of(new StateChange(target, previous, candidate, result, at, downFor));
    }

    private State candidate() {
        if (consecutiveFailures >= failuresBeforeDown) {
            return State.DOWN;
        }
        if (consecutiveSuccesses >= successesBeforeUp) {
            return State.UP;
        }
        return null;
    }

    public synchronized Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", target.name());
        json.put("url", target.url().toString());
        json.put("state", state.label());
        json.put("lastCheckedAt", lastCheckedAt == null ? null : lastCheckedAt.toString());
        json.put("lastChangeAt", lastChangeAt == null ? null : lastChangeAt.toString());
        json.put("upSince", upSince == null ? null : upSince.toString());
        json.put("httpStatus", last == null ? null : last.httpStatus());
        json.put("reportedStatus", last == null ? null : last.reportedStatus());
        json.put("detail", last == null ? null : last.detail());
        json.put("durationMillis", last == null ? null : last.durationMillis());
        // Latency is reported and never alerted on. A slow health endpoint is a real condition, but
        // "slow" needs a per-target baseline this app does not keep, and a threshold guessed for one
        // service is wrong for the next. Watch the number; do not expect to be woken by it.
        json.put("slowestMillis", slowestMillis);
        json.put("checksTotal", checksTotal);
        json.put("failuresTotal", failuresTotal);
        json.put("outagesTotal", outagesTotal);
        json.put("consecutiveFailures", consecutiveFailures);
        return json;
    }

    /** Just the state, for the summary on this app's own {@code /health}. */
    public synchronized Map<String, Object> summaryJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("state", state.label());
        json.put("detail", last == null ? null : last.detail());
        json.put("durationMillis", last == null ? null : last.durationMillis());
        json.put("lastCheckedAt", lastCheckedAt == null ? null : lastCheckedAt.toString());
        return json;
    }

}

package dev.orwell.bucket.fixture;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One fixture's running state, and the decision about when it has actually changed.
 *
 * <p>This is the whole detector, in the sense that matters. Measuring a region is arithmetic;
 * deciding that the lamp is now on is where a naive implementation produces something unusable, and
 * it fails in two specific ways:
 *
 * <ul>
 *   <li><strong>Flapping at the threshold.</strong> One threshold means a fixture reading right at it
 *       reports a change on every frame — at 1 fps, 3,600 state changes an hour from a lamp nobody
 *       touched. Two thresholds with a gap between them fix it: crossing up needs {@code onAbove},
 *       crossing down needs {@code offBelow}, and inside the gap the current state simply holds.
 *   <li><strong>Believing one frame.</strong> A passing shadow, a camera's auto-exposure hunting, a
 *       badly compressed frame — any of them can put one sample the wrong side of a threshold.
 *       {@code confirmFrames} consecutive agreeing samples are required, so at 1 fps a change costs
 *       a couple of seconds of latency and buys immunity to every one-frame artefact.
 * </ul>
 *
 * <p>It reports <em>transitions</em>, never states. Nothing is emitted while a lamp stays on, which
 * is what makes this cheap to alert on: one line when it goes on, one when it goes off, and silence
 * in between. The current state is on {@code /fixtures} for anyone who wants to poll it instead.
 *
 * <p>Written by the frame listener thread and read by request threads, so both the update and the
 * snapshot are synchronized on this object. Nothing else touches its fields.
 */
public final class FixtureWatch {
    /** Readings kept for calibration: one minute of history at 1 fps. */
    private static final int RECENT_READINGS = 60;

    /** Where a fixture is. {@link #UNKNOWN} until enough frames have agreed on an answer. */
    public enum State {
        UNKNOWN, ON, OFF;

        public String label() {
            return name().toLowerCase();
        }
    }

    /** A transition worth telling somebody about. */
    public record StateChange(String fixture, String source, State from, State to, double value,
            Instant at) {

        public Map<String, Object> asJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("fixture", fixture);
            json.put("source", source);
            json.put("previousState", from.label());
            json.put("state", to.label());
            json.put("value", value);
            json.put("at", at.toString());
            return json;
        }
    }

    private record Reading(double value, Instant at) {
    }

    private final Fixture fixture;
    private final Deque<Reading> recent = new ArrayDeque<>();

    private State state = State.UNKNOWN;
    private State pending;
    private int pendingCount;
    private boolean seen;
    private double lastValue;
    private double observedMin = Double.POSITIVE_INFINITY;
    private double observedMax = Double.NEGATIVE_INFINITY;
    private long samples;
    private long changesTotal;
    private Instant lastChangeAt;

    public FixtureWatch(Fixture fixture) {
        this.fixture = Objects.requireNonNull(fixture, "fixture");
    }

    public Fixture fixture() {
        return fixture;
    }

    public synchronized State state() {
        return state;
    }

    /**
     * Records one measurement.
     *
     * @return the transition, if this measurement completed one. Empty on every other frame, which
     *         is almost all of them.
     */
    public synchronized Optional<StateChange> update(double value, String source, Instant at) {
        samples++;
        seen = true;
        lastValue = value;
        observedMin = Math.min(observedMin, value);
        observedMax = Math.max(observedMax, value);
        recent.addLast(new Reading(value, at));
        while (recent.size() > RECENT_READINGS) {
            recent.removeFirst();
        }

        State candidate = candidateFrom(value);
        if (candidate == null || candidate == state) {
            // In the dead band, or already where it wants to be. Either way any part-completed
            // change is abandoned: confirmFrames means *consecutive*, so a single frame back inside
            // the band restarts the count rather than pausing it.
            pending = null;
            pendingCount = 0;
            return Optional.empty();
        }

        if (candidate == pending) {
            pendingCount++;
        } else {
            pending = candidate;
            pendingCount = 1;
        }
        if (pendingCount < fixture.confirmFrames()) {
            return Optional.empty();
        }

        State previous = state;
        state = candidate;
        pending = null;
        pendingCount = 0;
        changesTotal++;
        lastChangeAt = at;
        // The first confirmed reading is a real transition out of UNKNOWN, and it is reported: on a
        // restart somebody wants to be told the stove is currently on, not only when it changes.
        return Optional.of(
                new StateChange(fixture.name(), source, previous, candidate, value, at));
    }

    private State candidateFrom(double value) {
        if (value >= fixture.onAbove()) {
            return State.ON;
        }
        if (value <= fixture.offBelow()) {
            return State.OFF;
        }
        return null;
    }

    /**
     * Everything needed to configure this fixture, which is what {@code /fixtures} is for.
     *
     * <p>The reason {@code recent} is here: thresholds cannot be guessed, they have to be read off
     * the fixture. Flip the switch, then look at a minute of values and put {@code onAbove} and
     * {@code offBelow} either side of the gap between the two clusters. {@code observedMin} and
     * {@code observedMax} give the same thing over the whole run, which is what catches the daylight
     * case — a range far wider than the switch explains means the region is picking up ambient light
     * and wants a {@code reference}.
     */
    public synchronized Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", fixture.name());
        json.put("state", state.label());
        json.put("value", seen ? lastValue : null);
        json.put("unit", fixture.metric().unit());
        json.put("onAbove", fixture.onAbove());
        json.put("offBelow", fixture.offBelow());
        json.put("confirmFrames", fixture.confirmFrames());
        json.put("framesPending", pendingCount);
        json.put("pendingState", pending == null ? null : pending.label());
        json.put("observedMin", seen ? observedMin : null);
        json.put("observedMax", seen ? observedMax : null);
        json.put("samples", samples);
        json.put("changesTotal", changesTotal);
        json.put("lastChangeAt", lastChangeAt == null ? null : lastChangeAt.toString());
        json.put("definition", fixture.asJson());
        json.put("recent", recentReadings());
        return json;
    }

    /** Just the state, for the summary on {@code /health}. */
    public synchronized Map<String, Object> summaryJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("state", state.label());
        json.put("value", seen ? lastValue : null);
        json.put("samples", samples);
        json.put("changesTotal", changesTotal);
        json.put("lastChangeAt", lastChangeAt == null ? null : lastChangeAt.toString());
        return json;
    }

    private List<Map<String, Object>> recentReadings() {
        List<Map<String, Object>> readings = new ArrayList<>(recent.size());
        for (Reading reading : recent) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", reading.value());
            entry.put("at", reading.at().toString());
            readings.add(entry);
        }
        return readings;
    }
}

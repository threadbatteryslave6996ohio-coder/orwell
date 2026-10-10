package dev.orwell.bootstrap.schedule;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bookkeeping shared by a service that runs a job on a schedule, can also be poked on demand,
 * and reports what it has been doing on {@code GET /health}.
 *
 * <p>Two services in this repo are that shape — log-analyzer polls Loki and asks a model whether
 * anything matters, liveness-analyzer polls Loki and decides which clients have gone silent — and
 * they had this written out twice: the same single-flight {@link AtomicBoolean}, the same four
 * counters, the same {@code lastRunAt}/{@code lastAlertAt}/{@code lastError} triple, and the same
 * eight health keys built by hand. What differs between them is the work itself, which is why this
 * is a collaborator they hold rather than a base class they extend: neither run body has to be
 * bent into a template method to share the counters around it.
 *
 * <p>The job's <b>noun</b> keeps each service's existing wire contract intact — {@code "poll"}
 * yields {@code pollsTotal} and "A poll is already in progress.", {@code "check"} yields
 * {@code checksTotal} — so adopting this changed no payload either service already served.
 *
 * <p>Not thread-safe in the sense of serialising the work: {@link #runOnce} guarantees only that
 * two runs do not overlap. That is exactly the guarantee both callers need, because the scheduler
 * thread and the {@code /run-once} request thread both call it and a slow upstream can outlive the
 * interval.
 */
public final class ScheduledJob {

    /**
     * One run of the job. Receives the instant the run started — the same one recorded as
     * {@code lastRunAt} — so a body that needs "now" compares against the timestamp it is reported
     * under rather than reading the clock a second time.
     */
    @FunctionalInterface
    public interface Body {
        Map<String, Object> run(Instant startedAt) throws IOException, InterruptedException;
    }

    private final String noun;
    private final String runsTotalKey;
    private final String busySummary;

    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private final AtomicLong runsTotal = new AtomicLong();
    private final AtomicLong alertsSentTotal = new AtomicLong();
    private final AtomicLong alertsRejectedTotal = new AtomicLong();
    private final AtomicLong errorsTotal = new AtomicLong();
    private volatile Instant lastRunAt;
    private volatile Instant lastAlertAt;
    private volatile String lastError = "";

    /**
     * @param noun what one run is called, lower case and singular — {@code "poll"}, {@code "check"}.
     *             It names the counter on {@code /health} and appears in the busy message.
     */
    public ScheduledJob(String noun) {
        this.noun = Objects.requireNonNull(noun, "noun");
        this.runsTotalKey = noun + "sTotal";
        this.busySummary = "A " + noun + " is already in progress.";
    }

    /**
     * Runs the job unless one is already running, in which case it answers the busy envelope
     * without touching any counter — a skipped run is not a run, and it is not an error either.
     *
     * <p>The run is counted and stamped <em>inside</em> the guard, so {@code lastRunAt} always
     * refers to a run that actually happened.
     */
    public Map<String, Object> runOnce(Body body) throws IOException, InterruptedException {
        if (!inProgress.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("success", false);
            busy.put("skipped", true);
            busy.put("summary", busySummary);
            return busy;
        }
        try {
            runsTotal.incrementAndGet();
            Instant startedAt = Instant.now();
            lastRunAt = startedAt;
            return body.run(startedAt);
        } finally {
            inProgress.set(false);
        }
    }

    /**
     * {@link #runOnce} for the scheduler thread, which has nobody to throw to: a failure is
     * recorded and the schedule continues. An uncaught exception here would otherwise cancel all
     * future executions of the fixed-rate task, which is the quiet way one of these services stops
     * working entirely.
     */
    public void runSafely(Body body) {
        try {
            runOnce(body);
        } catch (Exception exception) {
            recordFailure(exception);
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Counts a failure and remembers it for {@code /health}.
     *
     * <p>{@code getMessage()} is null more often than people expect — an NPE's usually is — so the
     * exception's own {@code toString()} stands in rather than reporting a bare {@code null} that
     * says nothing about what went wrong.
     */
    public void recordFailure(Exception exception) {
        errorsTotal.incrementAndGet();
        String message = exception.getMessage();
        lastError = message == null || message.isBlank() ? exception.toString() : message;
    }

    /** An alert the alert service accepted. */
    public void recordAlertDelivered() {
        alertsSentTotal.incrementAndGet();
        lastAlertAt = Instant.now();
    }

    /**
     * An alert the alert service refused and will keep refusing. It is surfaced as
     * {@code lastError} rather than counted as a delivery, because a broken alert pipeline that
     * reports nothing is indistinguishable from one with nothing to report.
     */
    public void recordAlertRejected(String reason) {
        alertsRejectedTotal.incrementAndGet();
        lastError = reason;
    }

    /** True while a run is in flight, for a caller that wants to report it. */
    public boolean isRunning() {
        return inProgress.get();
    }

    /** What one run is called, for a caller composing its own summary. */
    public String noun() {
        return noun;
    }

    /**
     * The health keys both services share. A caller merges its own around these — the counter for
     * runs is named after the noun, so nothing here collides with an app's own keys.
     */
    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(runsTotalKey, runsTotal.get());
        details.put("alertsSentTotal", alertsSentTotal.get());
        details.put("alertsRejectedTotal", alertsRejectedTotal.get());
        details.put("errorsTotal", errorsTotal.get());
        details.put("lastRunAt", lastRunAt == null ? null : lastRunAt.toString());
        details.put("lastAlertAt", lastAlertAt == null ? null : lastAlertAt.toString());
        details.put("lastError", lastError);
        return details;
    }
}

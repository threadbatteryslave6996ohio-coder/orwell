package dev.orwell.bootstrap.schedule;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantees two live services depend on: that two runs never overlap, that a skipped run is
 * not counted as one, and that a scheduler thread cannot be killed by the work it runs.
 */
class ScheduledJobTest {

    @Test
    void namesItsCounterAfterTheNoun() {
        assertTrue(new ScheduledJob("poll").healthDetails().containsKey("pollsTotal"));
        assertTrue(new ScheduledJob("check").healthDetails().containsKey("checksTotal"));
    }

    @Test
    void countsAndStampsARunThatHappened() throws Exception {
        ScheduledJob job = new ScheduledJob("poll");

        job.runOnce(startedAt -> Map.of("success", true));

        Map<String, Object> details = job.healthDetails();
        assertEquals(1L, details.get("pollsTotal"));
        assertNotNull(details.get("lastRunAt"));
        assertEquals(0L, details.get("errorsTotal"));
    }

    /** The body sees the same instant reported as {@code lastRunAt}, not a second clock reading. */
    @Test
    void handsTheBodyTheInstantItIsReportedUnder() throws Exception {
        ScheduledJob job = new ScheduledJob("check");
        AtomicReference<Instant> seen = new AtomicReference<>();

        job.runOnce(startedAt -> {
            seen.set(startedAt);
            return Map.of();
        });

        assertEquals(seen.get().toString(), job.healthDetails().get("lastRunAt"));
    }

    /**
     * The whole reason this guard exists: the scheduler thread and a {@code /run-once} request can
     * arrive together, and a slow upstream makes that likely rather than theoretical.
     */
    @Test
    void refusesToRunTwoAtOnceAndDoesNotCountTheOneItRefused() throws Exception {
        ScheduledJob job = new ScheduledJob("poll");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger bodyRuns = new AtomicInteger();

        Thread first = new Thread(() -> job.runSafely(startedAt -> {
            bodyRuns.incrementAndGet();
            inside.countDown();
            release.await();
            return Map.of("success", true);
        }));
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the first run never started");

        Map<String, Object> concurrent = job.runOnce(startedAt -> {
            bodyRuns.incrementAndGet();
            return Map.of("success", true);
        });
        release.countDown();
        first.join(5_000);

        assertEquals(false, concurrent.get("success"));
        assertEquals(true, concurrent.get("skipped"));
        assertEquals("A poll is already in progress.", concurrent.get("summary"));
        assertEquals(1, bodyRuns.get(), "the refused call must not have run its body");
        // The refused call ran nothing, so it is neither a run nor an error.
        assertEquals(1L, job.healthDetails().get("pollsTotal"));
        assertEquals(0L, job.healthDetails().get("errorsTotal"));
    }

    /** The guard has to be released even when the body throws, or the service seizes up for good. */
    @Test
    void releasesTheGuardWhenTheBodyThrows() throws Exception {
        ScheduledJob job = new ScheduledJob("poll");

        assertThrows(IOException.class, () -> job.runOnce(startedAt -> {
            throw new IOException("Loki refused the query.");
        }));

        Map<String, Object> second = job.runOnce(startedAt -> Map.of("success", true));
        assertEquals(true, second.get("success"));
        assertEquals(2L, job.healthDetails().get("pollsTotal"));
        assertFalse(job.isRunning());
    }

    /**
     * An exception escaping a {@code @Scheduled} method cancels every future execution of the
     * task, which is the quiet way one of these services stops working. {@code runSafely} is what
     * stops that.
     */
    @Test
    void runSafelySwallowsAndCountsAFailure() {
        ScheduledJob job = new ScheduledJob("check");

        job.runSafely(startedAt -> {
            throw new IOException("Grafana proxy query failed with status 502");
        });

        Map<String, Object> details = job.healthDetails();
        assertEquals(1L, details.get("errorsTotal"));
        assertEquals("Grafana proxy query failed with status 502", details.get("lastError"));
        // A run that failed still happened.
        assertEquals(1L, details.get("checksTotal"));
    }

    /** A null message is common enough that reporting a bare "null" on /health is not acceptable. */
    @Test
    void reportsSomethingUsefulWhenTheExceptionHasNoMessage() {
        ScheduledJob job = new ScheduledJob("poll");

        job.recordFailure(new NullPointerException());

        String lastError = (String) job.healthDetails().get("lastError");
        assertNotNull(lastError);
        assertTrue(lastError.contains("NullPointerException"), "unhelpful lastError: " + lastError);
    }

    @Test
    void separatesDeliveredAlertsFromRejectedOnes() {
        ScheduledJob job = new ScheduledJob("check");

        job.recordAlertDelivered();
        job.recordAlertRejected("Alert service rejected the request.");

        Map<String, Object> details = job.healthDetails();
        assertEquals(1L, details.get("alertsSentTotal"));
        assertEquals(1L, details.get("alertsRejectedTotal"));
        assertNotNull(details.get("lastAlertAt"));
        // A rejection is not an error the job made; it is a pipeline fault worth seeing.
        assertEquals(0L, details.get("errorsTotal"));
        assertEquals("Alert service rejected the request.", details.get("lastError"));
    }

    @Test
    void reportsNoTimestampsBeforeAnythingHasRun() {
        Map<String, Object> details = new ScheduledJob("poll").healthDetails();

        assertNull(details.get("lastRunAt"));
        assertNull(details.get("lastAlertAt"));
        assertEquals("", details.get("lastError"));
        assertEquals(0L, details.get("pollsTotal"));
    }
}

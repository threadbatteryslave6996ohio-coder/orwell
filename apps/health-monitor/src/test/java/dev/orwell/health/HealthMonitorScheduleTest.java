package dev.orwell.health;

import dev.orwell.logging.LogEntry;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heartbeat, and this app's opinion of itself.
 *
 * <p>Both exist for one reason: this app's failure is the only failure that announces nothing, because
 * every other component's outage is reported *by* it and silence is its normal output. A heartbeat
 * something else can miss, and a {@code /health} that admits its own rounds have stopped, are what
 * make that failure detectable.
 */
class HealthMonitorScheduleTest {

    @Test
    void publishesAHeartbeatAfterEachCompletedRound() throws Exception {
        try (JsonStubServer stack = JsonStubServer.start()) {
            stack.stub("/health", 200, "{\"status\":\"healthy\"}");
            List<LogEntry> beats = new CopyOnWriteArrayList<>();
            HealthMonitorService service = service(stack, null);

            try (HealthMonitorSchedule schedule = new HealthMonitorSchedule(
                    service, 1, entry -> { }, Optional.of(beats::add))) {
                schedule.start();
                // The first round runs immediately, so one beat is due right away.
                waitUntil(() -> !beats.isEmpty());
            }

            assertThat(beats).isNotEmpty();
            LogEntry beat = beats.get(0);
            assertThat(beat.message()).isEqualTo("Health monitor heartbeat.");
            // clientId, because that is the field apps/liveness-analyzer groups heartbeats by — it is
            // what lets that app watch this one with configuration and no code.
            assertThat(beat.metadata()).containsEntry("clientId", "health-monitor");
            assertThat(beat.metadata()).containsEntry("targets", 1);
            assertThat(beat.metadata()).containsKey("targetsDown");
        }
    }

    /** A beat means "a round finished", so it must keep coming while a target is down. */
    @Test
    void keepsBeatingWhileATargetIsDown() throws Exception {
        List<LogEntry> beats = new CopyOnWriteArrayList<>();
        HealthMonitorService service = new HealthMonitorService(
                List.of(new HealthTarget("dead", URI.create("http://127.0.0.1:1/health"))),
                new HealthProbe(Duration.ofMillis(200), entry -> { }),
                2, 1, null, entry -> { });

        try (HealthMonitorSchedule schedule = new HealthMonitorSchedule(
                service, 1, entry -> { }, Optional.of(beats::add))) {
            schedule.start();
            waitUntil(() -> !beats.isEmpty() && service.downCount() > 0);
        }

        assertThat(beats).isNotEmpty();
        assertThat(beats.get(beats.size() - 1).metadata()).containsEntry("targetsDown", 1L);
    }

    /** No Loki, no heartbeat — and the rounds still run, which is the point of it being optional. */
    @Test
    void runsWithoutAHeartbeatChannel() throws Exception {
        try (JsonStubServer stack = JsonStubServer.start()) {
            stack.stub("/health", 200, "{\"status\":\"healthy\"}");
            HealthMonitorService service = service(stack, null);

            try (HealthMonitorSchedule schedule = new HealthMonitorSchedule(
                    service, 1, entry -> { }, Optional.empty())) {
                schedule.start();
                waitUntil(() -> service.healthDetails().get("roundsTotal").equals(1L));
            }

            assertThat(service.healthDetails()).containsEntry("roundsTotal", 1L);
        }
    }

    /**
     * The failure this is all for: up, listening, and not checking. A zero allowance makes any elapsed
     * time stale, which is the same condition a stopped scheduler produces after three intervals.
     */
    @Test
    void admitsWhenItsOwnRoundsHaveStopped() throws Exception {
        try (JsonStubServer stack = JsonStubServer.start()) {
            stack.stub("/health", 200, "{\"status\":\"healthy\"}");
            HealthMonitorService service = service(stack, null);

            // Before any round, judged against startup — so a scheduler that never ran is caught too,
            // rather than sitting at "no rounds yet" forever and reading as healthy.
            assertThat(service.checksAreCurrent(Duration.ofMinutes(1))).isTrue();
            Thread.sleep(5);
            assertThat(service.checksAreCurrent(Duration.ZERO)).isFalse();

            service.checkAll();

            assertThat(service.checksAreCurrent(Duration.ofMinutes(1))).isTrue();
            Thread.sleep(5);
            assertThat(service.checksAreCurrent(Duration.ZERO)).isFalse();
        }
    }

    private static HealthMonitorService service(JsonStubServer stack, String alertUrl) {
        return new HealthMonitorService(
                List.of(new HealthTarget("a", URI.create(stack.baseUrl() + "/health"))),
                new HealthProbe(Duration.ofSeconds(2), entry -> { }),
                2, 1, alertUrl, entry -> { });
    }

    /** Polls for a condition rather than sleeping a fixed time, so the test is not timing-fragile. */
    private static void waitUntil(java.util.function.BooleanSupplier condition) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IOException("condition never became true within 5s");
    }
}

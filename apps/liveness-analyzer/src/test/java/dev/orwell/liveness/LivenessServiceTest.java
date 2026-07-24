package dev.orwell.liveness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivenessServiceTest {
    private static final int THRESHOLD_SECONDS = 15;

    private final AtomicReference<Map<String, Instant>> heartbeats = new AtomicReference<>(Map.of());
    private final RecordingAlerter alerter = new RecordingAlerter();

    private LivenessService service(Set<String> expected) {
        HeartbeatSource source = lookback -> heartbeats.get();
        return new LivenessService(5, 60, THRESHOLD_SECONDS, expected, source, alerter,
                new AlertCooldownTracker(300));
    }

    @Test
    void freshHeartbeatIsAliveAndDoesNotAlert() throws Exception {
        heartbeats.set(Map.of("client-mac", Instant.now()));
        Map<String, Object> result = service(Set.of()).checkOnce();

        assertEquals(0, result.get("clientsDown"));
        assertTrue(alerter.downCalls.isEmpty());
    }

    @Test
    void silentClientPastThresholdAlerts() throws Exception {
        heartbeats.set(Map.of("client-mac", Instant.now().minusSeconds(3600)));
        Map<String, Object> result = service(Set.of()).checkOnce();

        assertEquals(1, result.get("clientsDown"));
        assertEquals(List.of("client-mac"), alerter.downCalls);
    }

    @Test
    void expectedClientNeverSeenAlerts() throws Exception {
        heartbeats.set(Map.of());
        Map<String, Object> result = service(Set.of("client-mac")).checkOnce();

        assertEquals(1, result.get("clientsDown"));
        assertEquals(List.of("client-mac"), alerter.downCalls);
    }

    @Test
    void cooldownSuppressesRepeatAlertWhileStillDown() throws Exception {
        heartbeats.set(Map.of("client-mac", Instant.now().minusSeconds(3600)));
        LivenessService service = service(Set.of());

        service.checkOnce();
        service.checkOnce();

        assertEquals(1, alerter.downCalls.size(), "second check within cooldown must not re-alert");
    }

    @Test
    void recoveryClearsCooldownSoNextOutageAlertsImmediately() throws Exception {
        LivenessService service = service(Set.of("client-mac"));

        heartbeats.set(Map.of("client-mac", Instant.now().minusSeconds(3600)));
        service.checkOnce();                                    // down -> alert #1
        heartbeats.set(Map.of("client-mac", Instant.now()));
        service.checkOnce();                                    // recovered -> cooldown cleared
        heartbeats.set(Map.of("client-mac", Instant.now().minusSeconds(3600)));
        service.checkOnce();                                    // down again -> alert #2

        assertEquals(2, alerter.downCalls.size());
    }

    @Test
    void transientFailureRollsBackCooldownAndRetries() throws Exception {
        alerter.outcome = Alerter.Outcome.FAILED;
        heartbeats.set(Map.of("client-mac", Instant.now().minusSeconds(3600)));
        LivenessService service = service(Set.of());

        service.checkOnce();
        service.checkOnce();

        assertEquals(2, alerter.downCalls.size(), "a FAILED delivery must not hold the cooldown");
    }

    private static final class RecordingAlerter implements Alerter {
        private final List<String> downCalls = new ArrayList<>();
        private Outcome outcome = Outcome.DELIVERED;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Outcome sendClientDown(String clientId, Instant lastSeen, int thresholdSeconds) {
            downCalls.add(clientId);
            return outcome;
        }
    }
}

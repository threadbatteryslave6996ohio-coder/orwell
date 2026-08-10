package dev.orwell.bucket.detection;

import com.sun.net.httpserver.HttpServer;
import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.entity.FrameSubscriptionEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.bucket.detection.repository.FrameSubscriptionRepository;
import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.orwell.bucket.detection.FrameTestFixtures.flat;
import static dev.orwell.bucket.detection.FrameTestFixtures.withBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bastion against a real Postgres: that frame bytes survive the round trip through
 * {@code bytea}, that the cursor queries actually select what they claim to, and that retention
 * deletes by age. These are the parts that compile fine and fail in production.
 *
 * <p>The scheduled jobs are configured with a very long interval and driven by hand, so a round is
 * something the test causes rather than races against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FrameBastionIntegrationTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void detectionProperties(DynamicPropertyRegistry registry) {
        registry.add("detection.alert-url", () -> "http://127.0.0.1:1/alerts");
        registry.add("detection.cooldown-seconds", () -> 60);
        registry.add("detection.min-confidence", () -> 0.35);
        registry.add("detection.motion.cell-threshold", () -> 12);
        registry.add("detection.motion.min-changed-fraction", () -> 0.02);
        registry.add("detection.fanout.mode", () -> "changed");
        registry.add("detection.auth.client-id", () -> "");
        registry.add("detection.auth.client-secret", () -> "");
        registry.add("detection.frame-retention-seconds", () -> 60);
        // Long enough that neither scheduled job fires on its own during a test.
        registry.add("detection.fanout.interval-seconds", () -> 3600);
        registry.add("detection.retention-sweep-seconds", () -> 3600);
    }

    @Autowired
    private FrameIngestService ingest;
    @Autowired
    private FrameDeliveryJob delivery;
    @Autowired
    private FrameRetentionJob retention;
    @Autowired
    private FrameEventRepository events;
    @Autowired
    private FrameSubscriptionRepository subscriptions;

    private HttpServer subscriberServer;
    private String baseUrl;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        events.deleteAll();
        subscriptions.deleteAll();
        received.clear();
        subscriberServer = HttpServer.create(new InetSocketAddress(0), 0);
        subscriberServer.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        subscriberServer.start();
        baseUrl = "http://127.0.0.1:" + subscriberServer.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void tearDown() {
        subscriberServer.stop(0);
    }

    @Test
    void frameBytesSurviveTheRoundTripThroughPostgres() {
        byte[] frame = flat(100);

        ingest.ingest(request("cam1", frame));

        List<FrameEventEntity> stored = events.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getFrameBytes()).isEqualTo(frame);
        assertThat(stored.getFirst().getSource()).isEqualTo("cam1");
    }

    @Test
    void aPushedFrameReachesASubscribedClient() {
        subscriptions.save(new FrameSubscriptionEntity("cam-client", baseUrl, null, 0L, Instant.now()));
        ingest.ingest(request("cam1", flat(100)));

        delivery.deliverPending();

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).contains("\"source\":\"cam1\"").contains("\"frameBase64\":\"");
        // The cursor was persisted, not just advanced in memory.
        assertThat(subscriptions.findAll().getFirst().getLastDeliveredId()).isGreaterThan(0L);
    }

    @Test
    void aSecondRoundDeliversNothingNew() {
        subscriptions.save(new FrameSubscriptionEntity("cam-client", baseUrl, null, 0L, Instant.now()));
        ingest.ingest(request("cam1", flat(100)));

        delivery.deliverPending();
        delivery.deliverPending();

        assertThat(received).hasSize(1);
    }

    @Test
    void aSourceScopedSubscriptionOnlyReceivesItsOwnSource() {
        subscriptions.save(new FrameSubscriptionEntity("cam-client", baseUrl, "cam2", 0L, Instant.now()));
        ingest.ingest(request("cam1", flat(100)));
        ingest.ingest(request("cam2", flat(200)));

        delivery.deliverPending();

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).contains("\"source\":\"cam2\"");
    }

    @Test
    void changedModeDoesNotStoreAStaticScene() {
        byte[] frame = flat(100);
        ingest.ingest(request("cam1", frame));
        ingest.ingest(request("cam1", frame));
        ingest.ingest(request("cam1", frame));

        // Only the baseline survives; two identical follow-ups were never written.
        assertThat(events.count()).isEqualTo(1);

        ingest.ingest(request("cam1", withBlock(100, 200)));

        assertThat(events.count()).isEqualTo(2);
    }

    @Test
    void retentionDropsAgedFramesAndKeepsFreshOnes() {
        Instant old = Instant.now().minus(2, ChronoUnit.HOURS);
        events.save(new FrameEventEntity("cam1", 1L, "sha-old", true, 0.3, old, flat(100)));
        ingest.ingest(request("cam1", flat(200)));
        assertThat(events.count()).isEqualTo(2);

        int deleted = retention.sweep();

        assertThat(deleted).isEqualTo(1);
        assertThat(events.findAll()).singleElement()
                .extracting(FrameEventEntity::getSha256).asString().isNotEqualTo("sha-old");
    }

    @Test
    void aNewSubscriptionStartsAtTheHeadRatherThanReplayingTheWindow() {
        ingest.ingest(request("cam1", flat(100)));
        long head = events.findTopByOrderByIdDesc().orElseThrow().getId();

        subscriptions.save(
                new FrameSubscriptionEntity("late-client", baseUrl, null, head, Instant.now()));
        delivery.deliverPending();

        // The frame that arrived before the subscription existed is not replayed.
        assertThat(received).isEmpty();

        ingest.ingest(request("cam1", withBlock(100, 200)));
        delivery.deliverPending();

        assertThat(received).hasSize(1);
    }

    private static Map<String, Object> request(String source, byte[] frame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("frameBase64", Base64.getEncoder().encodeToString(frame));
        return payload;
    }
}

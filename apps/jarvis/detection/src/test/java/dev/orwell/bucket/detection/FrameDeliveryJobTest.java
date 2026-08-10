package dev.orwell.bucket.detection;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.entity.FrameSubscriptionEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.bucket.detection.repository.FrameSubscriptionRepository;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.orwell.bucket.detection.FrameTestFixtures.flat;
import static dev.orwell.bucket.detection.FrameTestFixtures.frame;
import static dev.orwell.bucket.detection.FrameTestFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cursor-tracked fan-out: which frames a subscription is sent, in what order, and where its cursor
 * ends up when a subscriber fails. The failure cases are the point of the class — they are what
 * makes a subscriber that was down catch up rather than silently skip frames.
 */
class FrameDeliveryJobTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private HttpServer subscriberServer;
    private String baseUrl;
    private final Map<String, List<String>> received = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Integer>> responses = new ConcurrentHashMap<>();

    private FrameSubscriptionRepository subscriptions;
    private FrameEventRepository events;
    private FrameDeliveryJob job;

    @BeforeEach
    void startServer() throws IOException {
        subscriptions = mock(FrameSubscriptionRepository.class);
        events = mock(FrameEventRepository.class);
        when(subscriptions.save(any(FrameSubscriptionEntity.class)))
                .thenAnswer(call -> call.getArgument(0));

        subscriberServer = HttpServer.create(new InetSocketAddress(0), 0);
        subscriberServer.start();
        baseUrl = "http://127.0.0.1:" + subscriberServer.getAddress().getPort();

        // A blank client id means no auth session, so these tests need no auth server.
        FrameSender sender = new FrameSender("", "", "", NO_OP_LOGGER);
        job = new FrameDeliveryJob(subscriptions, events, sender, NO_OP_LOGGER);
    }

    @AfterEach
    void stopServer() {
        subscriberServer.stop(0);
    }

    @Test
    void deliversPendingFramesInOrderAndAdvancesTheCursor() {
        String url = hook("/all");
        FrameSubscriptionEntity subscription = subscription(1L, "cam-client", url, null, 0L);
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(subscription));
        when(events.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(frame(1L, "cam1", flat(100)), frame(2L, "cam1", flat(120))));

        job.deliverPending();

        assertThat(received.get("/all")).hasSize(2);
        assertThat(received.get("/all").get(0)).contains("\"frameId\":1");
        assertThat(received.get("/all").get(1)).contains("\"frameId\":2");
        assertThat(subscription.getLastDeliveredId()).isEqualTo(2L);
        assertThat(job.framesDeliveredTotal()).isEqualTo(2L);
    }

    @Test
    void theDeliveredBodyCarriesTheFrameBytes() {
        String url = hook("/body");
        when(subscriptions.findByActiveTrueOrderByIdAsc())
                .thenReturn(List.of(subscription(1L, "cam-client", url, null, 0L)));
        when(events.findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(frame(7L, "cam1", flat(100))));

        job.deliverPending();

        String body = received.get("/body").getFirst();
        assertThat(body).contains("\"source\":\"cam1\"").contains("\"sha256\":\"sha-7\"")
                .contains("\"frameBase64\":\"");
    }

    @Test
    void aRejectedFrameLeavesTheCursorAndHoldsBackTheFramesBehindIt() {
        String url = hook("/flaky", 200, 500);
        FrameSubscriptionEntity subscription = subscription(1L, "cam-client", url, null, 0L);
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(subscription));
        when(events.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(
                        frame(1L, "cam1", flat(100)),
                        frame(2L, "cam1", flat(120)),
                        frame(3L, "cam1", flat(140))));

        job.deliverPending();

        // Frame 2 was rejected, so 3 must not have been attempted — a subscriber never sees a
        // later frame before the one it refused.
        assertThat(received.get("/flaky")).hasSize(2);
        assertThat(subscription.getLastDeliveredId()).isEqualTo(1L);
    }

    @Test
    void theNextRoundResendsTheFrameThatFailed() {
        String url = hook("/retry", 500, 200);
        FrameSubscriptionEntity subscription = subscription(1L, "cam-client", url, null, 0L);
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(subscription));
        when(events.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(frame(1L, "cam1", flat(100))));

        job.deliverPending();
        assertThat(subscription.getLastDeliveredId()).isEqualTo(0L);

        job.deliverPending();

        assertThat(received.get("/retry")).hasSize(2);
        assertThat(subscription.getLastDeliveredId()).isEqualTo(1L);
    }

    @Test
    void aSourceScopedSubscriptionOnlyQueriesItsOwnSource() {
        String url = hook("/scoped");
        when(subscriptions.findByActiveTrueOrderByIdAsc())
                .thenReturn(List.of(subscription(1L, "cam-client", url, "cam2", 0L)));
        when(events.findByIdGreaterThanAndSourceOrderByIdAsc(eq(0L), eq("cam2"), any(Pageable.class)))
                .thenReturn(List.of(frame(5L, "cam2", flat(100))));

        job.deliverPending();

        assertThat(received.get("/scoped")).hasSize(1);
        assertThat(received.get("/scoped").getFirst()).contains("\"source\":\"cam2\"");
        verify(events, never()).findByIdGreaterThanOrderByIdAsc(anyLong(), any(Pageable.class));
    }

    @Test
    void oneFailingSubscriptionDoesNotBlockAnother() {
        String failing = hook("/down", 500);
        String healthy = hook("/up");
        FrameSubscriptionEntity down = subscription(1L, "a", failing, null, 0L);
        FrameSubscriptionEntity up = subscription(2L, "b", healthy, null, 0L);
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(down, up));
        when(events.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(frame(1L, "cam1", flat(100))));

        job.deliverPending();

        assertThat(down.getLastDeliveredId()).isEqualTo(0L);
        assertThat(up.getLastDeliveredId()).isEqualTo(1L);
        assertThat(received.get("/up")).hasSize(1);
    }

    @Test
    void aSubscriberThatIsUnreachableEntirelyLeavesItsCursorAlone() {
        FrameSubscriptionEntity subscription =
                subscription(1L, "cam-client", "http://127.0.0.1:1/nowhere", null, 0L);
        when(subscriptions.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(subscription));
        when(events.findByIdGreaterThanOrderByIdAsc(eq(0L), any(Pageable.class)))
                .thenReturn(List.of(frame(1L, "cam1", flat(100))));

        job.deliverPending();

        assertThat(subscription.getLastDeliveredId()).isEqualTo(0L);
    }

    /** Registers a hook path that always answers 200. */
    private String hook(String path) {
        return hook(path, new int[0]);
    }

    /** Registers a hook path answering the given statuses in order, then 200 forever after. */
    private String hook(String path, int... statuses) {
        received.put(path, new CopyOnWriteArrayList<>());
        ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();
        for (int status : statuses) {
            queue.add(status);
        }
        responses.put(path, queue);
        subscriberServer.createContext(path, exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.get(path).add(new String(body, StandardCharsets.UTF_8));
            Integer status = responses.get(path).poll();
            respond(exchange, status == null ? 200 : status);
        });
        return baseUrl + path;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}

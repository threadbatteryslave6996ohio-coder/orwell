package dev.orwell.bucket.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bucket.detection.repository.FrameCursorRepository;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.orwell.bucket.detection.FrameTestFixtures.flat;
import static dev.orwell.bucket.detection.FrameTestFixtures.request;
import static dev.orwell.bucket.detection.FrameTestFixtures.withBlock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hub end to end over a real HTTP server and a real Postgres: a producer pushes a frame, it is
 * stored, and it reaches both the clients connected at the time and the ones that connect later
 * and ask to catch up. The replay-then-live handover is the part that can only be proven here.
 *
 * <p>Each test uses its own source name so that a scoped client cannot see another test's frames,
 * and so a leftover connection from a finished test cannot count as a recipient here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrameStreamIntegrationTest extends PostgresIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void detectionProperties(DynamicPropertyRegistry registry) {
        registry.add("detection.alert-url", () -> "http://127.0.0.1:1/alerts");
        registry.add("detection.cooldown-seconds", () -> 60);
        registry.add("detection.min-confidence", () -> 0.35);
        registry.add("detection.motion.cell-threshold", () -> 12);
        registry.add("detection.motion.min-changed-fraction", () -> 0.02);
        registry.add("detection.stream.queue-depth", () -> 8);
        registry.add("detection.store.mode", () -> "async");
        registry.add("detection.store.queue-depth", () -> 512);
        registry.add("detection.frame-retention-seconds", () -> 60);
        // Long enough that the retention sweep never fires mid-test.
        registry.add("detection.retention-sweep-seconds", () -> 3600);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private FrameEventRepository events;
    @Autowired
    private FrameCursorRepository cursors;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<StreamClient> clients = new ArrayList<>();

    @BeforeEach
    void clearStore() {
        events.deleteAll();
        cursors.deleteAll();
    }

    /**
     * A hung-up SSE client stays registered until a write to it fails, so simply closing the
     * sockets would leave this test's dead connections counting as recipients in the next test.
     * Pushing frames until nobody is left is what actually makes the tests independent — and it
     * has to cover each source a client was watching, since a scoped client never sees a frame
     * from another camera and so never fails a write.
     */
    @AfterEach
    void closeClients() {
        Set<String> sources = clients.stream()
                .map(client -> client.source == null ? "cam-teardown" : client.source)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        sources.add("cam-teardown");
        clients.forEach(StreamClient::close);
        clients.clear();
        sources.forEach(this::drainUntilNobodyIsConnected);
    }

    private void drainUntilNobodyIsConnected(String source) {
        await(() -> {
            try {
                return Integer.valueOf(0).equals(push(source, flat(100)).get("recipients"));
            } catch (Exception exception) {
                return false;
            }
        });
    }

    // --- live relay -------------------------------------------------------------------------

    @Test
    void aPushedFrameReachesAConnectedClient() throws Exception {
        StreamClient viewer = connect(null);

        Map<String, Object> push = push("cam-a", flat(100));

        assertThat(push.get("stored")).isEqualTo(true);
        assertThat(push.get("recipients")).isEqualTo(1);
        await(() -> viewer.frames.size() == 1);
        Map<String, Object> frame = viewer.frame(0);
        assertThat(frame.get("source")).isEqualTo("cam-a");
        assertThat(frame.get("frameId")).isEqualTo(push.get("frameId"));
        assertThat((String) frame.get("frameBase64")).isNotEmpty();
    }

    @Test
    void everyConnectedClientGetsTheSameFrame() throws Exception {
        StreamClient first = connect(null);
        StreamClient second = connect(null);

        Map<String, Object> push = push("cam-b", flat(100));

        assertThat(push.get("recipients")).isEqualTo(2);
        await(() -> first.frames.size() == 1 && second.frames.size() == 1);
    }

    @Test
    void aSourceScopedClientOnlyGetsItsOwnCamera() throws Exception {
        StreamClient scoped = connect("cam-wanted");

        push("cam-unwanted", flat(100));
        Map<String, Object> wanted = push("cam-wanted", flat(200));

        assertThat(wanted.get("recipients")).isEqualTo(1);
        await(() -> scoped.frames.size() == 1);
        assertThat(scoped.frame(0).get("source")).isEqualTo("cam-wanted");
    }

    @Test
    void aStaticSceneStillCostsAFramePerPush() throws Exception {
        StreamClient viewer = connect(null);
        byte[] frame = flat(100);

        push("cam-static", frame);
        push("cam-static", frame);
        push("cam-static", frame);

        // The hub relays what it is given rather than deciding a repeat is not worth sending, so
        // the retention window is the only thing bounding the table.
        await(() -> viewer.frames.size() == 3);
        // Counted per source, not over the table: the teardown drain pushes frames of its own, and
        // its async writes can land after the next test has cleared the store.
        await(() -> events.countBySource("cam-static") == 3);
    }

    // --- storage and replay -----------------------------------------------------------------

    @Test
    void aFramePushedWithNobodyConnectedIsStillStored() throws Exception {
        Map<String, Object> push = pushAndStore("cam-nobody", flat(100));

        assertThat(push.get("stored")).isEqualTo(true);
        assertThat(push.get("recipients")).isEqualTo(0);
        // Broadcast and storage are separate now: nobody received it, and it still landed.
        assertThat(events.countBySource("cam-nobody")).isEqualTo(1);
    }

    @Test
    void aPlainConnectionStartsAtTheHeadRatherThanReplayingTheWindow() throws Exception {
        pushAndStore("cam-head", flat(100));

        StreamClient viewer = connect(null);
        Map<String, Object> afterConnecting = pushAndStore("cam-head", withBlock(100, 200));

        await(() -> viewer.frames.size() == 1);
        Thread.sleep(300);
        // Only the frame pushed after it connected.
        assertThat(viewer.frames).hasSize(1);
        assertThat(viewer.frame(0).get("frameId")).isEqualTo(afterConnecting.get("frameId"));
    }

    @Test
    void fromZeroReplaysEverythingStillStored() throws Exception {
        pushAndStore("cam-replay", flat(100));
        pushAndStore("cam-replay", withBlock(100, 200));
        pushAndStore("cam-replay", flat(60));

        StreamClient viewer = connect(null, null, 0L);

        await(() -> viewer.frames.size() == 3);
        assertThat(viewer.frame(0).get("source")).isEqualTo("cam-replay");
        // Replayed oldest first, in id order.
        long first = ((Number) viewer.frame(0).get("frameId")).longValue();
        long last = ((Number) viewer.frame(2).get("frameId")).longValue();
        assertThat(first).isLessThan(last);
    }

    @Test
    void replayHandsOverToLiveWithoutGapsOrDuplicates() throws Exception {
        pushAndStore("cam-handover", flat(100));
        pushAndStore("cam-handover", withBlock(100, 200));

        StreamClient viewer = connect(null, null, 0L);
        await(() -> viewer.frames.size() == 2);

        pushAndStore("cam-handover", flat(60));

        await(() -> viewer.frames.size() == 3);
        Thread.sleep(300);
        assertThat(viewer.frames).hasSize(3);
        List<Long> ids = viewer.frameIds();
        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void anExplicitFromResumesAfterThatFrame() throws Exception {
        long firstId = ((Number) pushAndStore("cam-from", flat(100)).get("frameId")).longValue();
        pushAndStore("cam-from", withBlock(100, 200));
        pushAndStore("cam-from", flat(60));

        StreamClient viewer = connect(null, null, firstId);

        await(() -> viewer.frames.size() == 2);
        Thread.sleep(300);
        // The frame named by `from` is not re-sent; only what came after it.
        assertThat(viewer.frames).hasSize(2);
        assertThat(viewer.frameIds()).allMatch(id -> id > firstId);
    }

    @Test
    void aScopedReplayOnlyReturnsThatSource() throws Exception {
        pushAndStore("cam-mix-a", flat(100));
        pushAndStore("cam-mix-b", flat(200));
        pushAndStore("cam-mix-a", withBlock(100, 200));

        StreamClient viewer = connect("cam-mix-a", null, 0L);

        await(() -> viewer.frames.size() == 2);
        Thread.sleep(300);
        assertThat(viewer.frames).hasSize(2);
        assertThat(viewer.frames.stream().map(this::source)).containsOnly("cam-mix-a");
    }

    // --- durable cursors --------------------------------------------------------------------

    @Test
    void aNamedSubscriptionRecordsWhereItGotTo() throws Exception {
        StreamClient viewer = connect(null, "sub-record", null);
        long frameId = ((Number) push("cam-cursor", flat(100)).get("frameId")).longValue();
        await(() -> viewer.frames.size() == 1);

        // The cursor is written on a throttle and forced on disconnect.
        viewer.close();
        drainUntilNobodyIsConnected("cam-cursor");

        await(() -> cursors.findByName("sub-record")
                .filter(cursor -> cursor.getLastDeliveredId() >= frameId).isPresent());
    }

    @Test
    void aNamedSubscriptionResumesFromItsStoredCursor() throws Exception {
        StreamClient first = connect(null, "sub-resume", null);
        pushAndStore("cam-resume", flat(100));
        await(() -> first.frames.size() == 1);
        first.close();
        drainUntilNobodyIsConnected("cam-resume");

        long resumedAfter = cursors.findByName("sub-resume").orElseThrow().getLastDeliveredId();
        // Pushed while the subscription had nobody connected.
        long missedId = ((Number) pushAndStore("cam-resume", flat(60)).get("frameId")).longValue();

        StreamClient second = connect(null, "sub-resume", null);

        // It picks up from the stored cursor, so the frame pushed while it was away arrives
        // without anything new being produced.
        await(() -> second.frameIds().contains(missedId));
        assertThat(second.resumingAfter()).isEqualTo(resumedAfter);
        assertThat(second.frameIds()).allMatch(id -> id > resumedAfter);
    }

    @Test
    void anUnknownSubscriptionNameStartsAtTheHead() throws Exception {
        pushAndStore("cam-fresh", flat(100));

        StreamClient viewer = connect(null, "sub-never-seen", null);
        pushAndStore("cam-fresh", withBlock(100, 200));

        await(() -> viewer.frames.size() == 1);
        Thread.sleep(300);
        // A new name is not an invitation to replay the whole window.
        assertThat(viewer.frames).hasSize(1);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Pushes and then waits for the async writer to land it, for tests that replay from store. */
    private Map<String, Object> pushAndStore(String source, byte[] frame) throws Exception {
        Map<String, Object> push = push(source, frame);
        long id = ((Number) push.get("frameId")).longValue();
        await(() -> events.findById(id).isPresent());
        return push;
    }

    private Map<String, Object> push(String source, byte[] frame) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base() + "/frames"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(request(source, frame))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), Map.class);
    }

    private StreamClient connect(String source) {
        return connect(source, null, null);
    }

    private StreamClient connect(String source, String subscription, Long from) {
        StringBuilder url = new StringBuilder(base()).append("/frames/stream?x=1");
        if (source != null) {
            url.append("&source=").append(source);
        }
        if (subscription != null) {
            url.append("&subscription=").append(subscription);
        }
        if (from != null) {
            url.append("&from=").append(from);
        }
        StreamClient client = new StreamClient(url.toString(), source);
        clients.add(client);
        // The hub sends a `connected` event as soon as the stream opens; waiting for it removes
        // the race between registering and the first push.
        await(() -> client.connected.get() != null);
        return client;
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String source(String rawFrame) {
        try {
            return (String) json.readValue(rawFrame, Map.class).get("source");
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("condition not met within " + TIMEOUT);
    }

    /** Reads an SSE stream on its own thread, splitting `connected` from `frame` events. */
    private final class StreamClient {
        private final String source;
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> connected = new AtomicReference<>();
        private final HttpClient client = HttpClient.newHttpClient();
        private final Thread reader;

        private StreamClient(String url, String source) {
            this.source = source;
            reader = Thread.ofVirtual().start(() -> {
                try {
                    HttpResponse<Stream<String>> response = client.send(
                            HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofLines());
                    String[] currentEvent = {""};
                    response.body().forEach(line -> {
                        if (line.startsWith("event:")) {
                            currentEvent[0] = line.substring("event:".length()).trim();
                        } else if (line.startsWith("data:")) {
                            String data = line.substring("data:".length()).trim();
                            if ("connected".equals(currentEvent[0])) {
                                connected.set(data);
                            } else {
                                frames.add(data);
                            }
                        }
                    });
                } catch (Exception ignored) {
                    // Closing the client mid-read is how this thread ends.
                }
            });
        }

        private Map<String, Object> frame(int index) {
            try {
                return json.readValue(frames.get(index), Map.class);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private List<Long> frameIds() {
            return frames.stream().map(raw -> {
                try {
                    return ((Number) json.readValue(raw, Map.class).get("frameId")).longValue();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toList();
        }

        private long resumingAfter() {
            try {
                return ((Number) json.readValue(connected.get(), Map.class).get("resumingAfter"))
                        .longValue();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        private void close() {
            // shutdownNow, not close: close() blocks until in-flight operations finish, and an SSE
            // stream never finishes on its own — that deadlocks the teardown.
            client.shutdownNow();
            reader.interrupt();
        }
    }
}

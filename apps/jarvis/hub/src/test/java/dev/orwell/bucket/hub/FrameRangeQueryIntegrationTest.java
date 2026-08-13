package dev.orwell.bucket.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bucket.hub.entity.FrameEventEntity;
import dev.orwell.bucket.hub.repository.FrameEventRepository;
import dev.orwell.primitives.Sha256;
import dev.orwell.testing.PostgresIntegrationTest;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.orwell.bucket.frame.FrameFixtures.flat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /frames} over real HTTP against a real Postgres: the time filter, the paging contract
 * a caller loops on, and the 400s.
 *
 * <p>Rows are written straight through the repository rather than pushed to {@code POST /frames},
 * because ingest stamps {@code capturedAt} with {@code Instant.now()} — a test that pushed frames
 * could only ever query a window a few milliseconds wide, which is the one shape of window this
 * endpoint is not for.
 *
 * <p>Two details keep this independent of its siblings, which share the Spring context and whose
 * async writes can land after this class has cleared the store. The window is in 2020, so a stray
 * frame — stamped {@code now} — falls outside every bounded query here. And the ids start at
 * {@link #BASE}, far above anything {@code frame_events_seq} will hand out during a test run, so a
 * late-arriving row cannot collide with a seeded one and fail the insert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrameRangeQueryIntegrationTest extends PostgresIntegrationTest {
    private static final Instant TEN = Instant.parse("2020-01-01T10:00:00Z");
    private static final long BASE = 1_000_000L;
    private static final int SEEDED = 9;

    @DynamicPropertySource
    static void hubProperties(DynamicPropertyRegistry registry) {
        registry.add("hub.stream.queue-depth", () -> 8);
        registry.add("hub.store.mode", () -> "async");
        registry.add("hub.store.queue-depth", () -> 512);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private FrameEventRepository events;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seed() {
        events.deleteAll();
        // One frame a minute from 10:00, alternating cameras: frames 1..9, even ones on cam-b.
        List<FrameEventEntity> frames = new ArrayList<>();
        for (int index = 1; index <= SEEDED; index++) {
            frames.add(frame(index, index % 2 == 0 ? "cam-b" : "cam-a",
                    TEN.plusSeconds((index - 1) * 60L)));
        }
        events.saveAll(frames);
    }

    // --- the window -----------------------------------------------------------------------------

    @Test
    void itReturnsOnlyTheFramesCapturedInTheWindow() throws Exception {
        Map<String, Object> response = get("?from=" + at(3) + "&to=" + at(6));

        // 10:02, 10:03, 10:04 — the frame at 10:05 belongs to the next window, since `to` is
        // exclusive.
        assertThat(frameIds(response)).containsExactly(ids(3, 4, 5));
        assertThat(response.get("hasMore")).isEqualTo(false);
    }

    @Test
    void anOmittedBoundIsOpenEnded() throws Exception {
        assertThat(frameIds(get("?from=" + at(8)))).containsExactly(ids(8, 9));
        assertThat(frameIds(get("?to=" + at(3)))).containsExactly(ids(1, 2));
        // Neither bound: everything, oldest first. Subsequence rather than exactly, because a
        // sibling test's late write is a row in this table too — it is stamped `now`, so it sorts
        // after these and cannot land between them.
        assertThat(frameIds(get(""))).containsSubsequence(ids(1, 2, 3, 4, 5, 6, 7, 8, 9));
    }

    @Test
    void aWindowWithNoFramesIsAnEmptyPage() throws Exception {
        Map<String, Object> response = get("?from=" + at(1) + "&to=" + at(1));

        assertThat(response.get("success")).isEqualTo(true);
        assertThat(response.get("returned")).isEqualTo(0);
        assertThat(response.get("hasMore")).isEqualTo(false);
        assertThat(response.get("nextAfter")).isNull();
    }

    @Test
    void sourceScopesTheWindowToOneCamera() throws Exception {
        Map<String, Object> response = get("?source=cam-b&to=" + at(SEEDED + 1));

        assertThat(frameIds(response)).containsExactly(ids(2, 4, 6, 8));
        assertThat(response.get("source")).isEqualTo("cam-b");
    }

    // --- paging ---------------------------------------------------------------------------------

    @Test
    void aCallerWalksTheWholeWindowByFollowingNextAfter() throws Exception {
        String window = "?to=" + at(SEEDED + 1) + "&limit=4";
        List<Long> collected = new ArrayList<>();
        Map<String, Object> page = get(window);
        collected.addAll(frameIds(page));

        while (Boolean.TRUE.equals(page.get("hasMore"))) {
            page = get(window + "&after=" + page.get("nextAfter"));
            collected.addAll(frameIds(page));
        }

        // Every frame exactly once, in id order, across three pages — the last of which is short
        // and says so.
        assertThat(collected).containsExactly(ids(1, 2, 3, 4, 5, 6, 7, 8, 9));
        assertThat(page.get("nextAfter")).isNull();
    }

    @Test
    void theCursorAndTheWindowApplyTogether() throws Exception {
        Map<String, Object> response =
                get("?from=" + at(3) + "&to=" + at(7) + "&after=" + (BASE + 4));

        // Frames 3..6 are in the window; the cursor drops the two already delivered.
        assertThat(frameIds(response)).containsExactly(ids(5, 6));
    }

    @Test
    void aQueriedFrameCarriesTheBytesAndLooksLikeAStreamedOne() throws Exception {
        Map<String, Object> response = get("?limit=1&to=" + at(SEEDED + 1));

        @SuppressWarnings("unchecked")
        Map<String, Object> frame = ((List<Map<String, Object>>) response.get("frames")).get(0);
        assertThat(frame).containsOnlyKeys(
                "frameId", "source", "frameIndex", "capturedAt", "sha256", "frameBase64");
        assertThat(frame.get("source")).isEqualTo("cam-a");
        assertThat(frame.get("capturedAt")).isEqualTo(TEN.toString());
        assertThat((String) frame.get("sha256")).hasSize(64);
        assertThat((String) frame.get("frameBase64")).isNotEmpty();
    }

    // --- refusals -------------------------------------------------------------------------------

    @Test
    void aMalformedTimestampIsAFourHundredInTheSharedErrorShape() throws Exception {
        HttpResponse<String> response = raw("?from=yesterday");

        assertThat(response.statusCode()).isEqualTo(400);
        Map<String, Object> body = json.readValue(response.body(), Map.class);
        assertThat(body.get("success")).isEqualTo(false);
        // Names the parameter and the format, rather than Spring's own conversion-failure body.
        assertThat((String) body.get("error")).contains("from").contains("ISO-8601");
    }

    @Test
    void aBackwardsWindowAndAnOversizedLimitAreBothRefused() throws Exception {
        HttpResponse<String> backwards = raw("?from=" + at(9) + "&to=" + at(1));
        assertThat(backwards.statusCode()).isEqualTo(400);

        HttpResponse<String> oversized = raw("?limit=" + (FrameQueryService.MAX_LIMIT + 1));
        assertThat(oversized.statusCode()).isEqualTo(400);
        assertThat((String) json.readValue(oversized.body(), Map.class).get("error"))
                .contains("limit");
    }

    @Test
    void aNonNumericLimitIsAFourHundredNotAFiveHundred() throws Exception {
        HttpResponse<String> response = raw("?limit=lots");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat((String) json.readValue(response.body(), Map.class).get("error"))
                .contains("limit");
    }

    // --- helpers --------------------------------------------------------------------------------

    private static FrameEventEntity frame(int number, String source, Instant capturedAt) {
        byte[] bytes = flat(100 + number);
        return new FrameEventEntity(
                BASE + number, source, (long) number, Sha256.hex(bytes), capturedAt, bytes);
    }

    /** The capture time of seeded frame {@code number}, one-based. */
    private static Instant at(int number) {
        return TEN.plusSeconds((number - 1) * 60L);
    }

    private static Long[] ids(int... numbers) {
        return java.util.Arrays.stream(numbers).mapToObj(number -> BASE + number).toArray(Long[]::new);
    }

    private Map<String, Object> get(String query) throws Exception {
        HttpResponse<String> response = raw(query);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readValue(response.body(), Map.class);
    }

    private HttpResponse<String> raw(String query) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/frames" + query))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static List<Long> frameIds(Map<String, Object> response) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) response.get("frames");
        return frames.stream().map(frame -> ((Number) frame.get("frameId")).longValue()).toList();
    }
}

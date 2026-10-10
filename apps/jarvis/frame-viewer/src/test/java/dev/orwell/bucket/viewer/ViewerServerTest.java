package dev.orwell.bucket.viewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.testing.PostgresJdbcTest;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.Undertow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The routes over real HTTP, because the things most likely to be wrong about them are not visible
 * from the Java: a content type, a cache header, a status code.
 *
 * <p>Two servers, because whether the upload route exists at all depends on configuration: one with no
 * hub, which is the default and must answer 503, and one with a hub address that is never reached,
 * which is enough to exercise the validation in front of it.
 */
class ViewerServerTest extends PostgresJdbcTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOON = Instant.parse("2026-08-17T12:00:00Z");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private static Undertow readOnly;
    private static Undertow withUploads;
    private static String readOnlyBase;
    private static String withUploadsBase;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startServers() {
        readOnly = start(backfill(""));
        withUploads = start(backfill("http://127.0.0.1:1"));
        readOnlyBase = baseUrl(readOnly);
        withUploadsBase = baseUrl(withUploads);
    }

    @AfterAll
    static void stopServers() {
        readOnly.stop();
        withUploads.stop();
    }

    @BeforeEach
    void createTable() throws SQLException {
        execute("DROP TABLE IF EXISTS frame_events",
                "CREATE TABLE frame_events ("
                        + "id bigint PRIMARY KEY,"
                        + "source varchar(255) NOT NULL,"
                        + "frame_index bigint,"
                        + "sha256 varchar(64) NOT NULL,"
                        + "captured_at timestamp(6) with time zone NOT NULL,"
                        + "frame_bytes bytea NOT NULL)");
    }

    // -----------------------------------------------------------------------
    // The page
    // -----------------------------------------------------------------------

    @Test
    void servesThePageUnderARestrictivePolicy() throws Exception {
        HttpResponse<String> response = get(readOnlyBase + "/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).get()
                .asString().contains("text/html");
        // No inline script may run, and nothing may be loaded from anywhere but this origin. A frame
        // is stored bytes from whoever can reach the hub, so this is the page's actual defence.
        assertThat(response.headers().firstValue("content-security-policy")).get()
                .asString().contains("script-src 'self'").contains("default-src 'none'");
        assertThat(response.body()).contains("stored frames");
    }

    @Test
    void servesTheScriptAsItsOwnResource() throws Exception {
        HttpResponse<String> response = get(readOnlyBase + "/viewer.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).get()
                .asString().contains("javascript");
    }

    // -----------------------------------------------------------------------
    // Frames
    // -----------------------------------------------------------------------

    @Test
    void opensOnTheLatestFrameAndNamesItsNeighbours() throws Exception {
        store(10, "cam1", NOON);
        store(11, "cam1", NOON.plusSeconds(1));
        store(12, "cam1", NOON.plusSeconds(2));

        JsonNode latest = json(readOnlyBase + "/api/frame?source=cam1");

        assertThat(latest.path("frame").path("frameId").asLong()).isEqualTo(12);
        assertThat(latest.path("olderFrameId").asLong()).isEqualTo(11);
        assertThat(latest.path("newerFrameId").isNull()).isTrue();
        assertThat(latest.path("position").asInt()).isEqualTo(3);
        assertThat(latest.path("total").asInt()).isEqualTo(3);
        // Metadata only: the bytes are a separate, cacheable request.
        assertThat(latest.path("frame").has("frameBase64")).isFalse();
        assertThat(latest.path("frame").path("imageUrl").asText())
                .isEqualTo("api/frames/12/image");
    }

    @Test
    void stepsBackAndForwardOneFrameAtATime() throws Exception {
        store(10, "cam1", NOON);
        store(11, "cam1", NOON.plusSeconds(1));

        JsonNode back = json(readOnlyBase + "/api/frame?source=cam1&before=11");
        assertThat(back.path("frame").path("frameId").asLong()).isEqualTo(10);

        JsonNode forward = json(readOnlyBase + "/api/frame?source=cam1&after=10");
        assertThat(forward.path("frame").path("frameId").asLong()).isEqualTo(11);
    }

    /** Running out of history is the end of the window, not an error. */
    @Test
    void answersWithNoFrameRatherThanAnErrorAtTheEndOfTheWindow() throws Exception {
        store(10, "cam1", NOON);

        JsonNode past = json(readOnlyBase + "/api/frame?source=cam1&before=10");

        assertThat(past.path("success").asBoolean()).isTrue();
        assertThat(past.path("frame").isNull()).isTrue();
    }

    @Test
    void listsTheDevicesThatHaveFramesStored() throws Exception {
        store(10, "cam1", NOON);
        store(11, "cam2", NOON.plusSeconds(1));

        JsonNode sources = json(readOnlyBase + "/api/sources");

        assertThat(sources.path("sources")).hasSize(2);
        assertThat(sources.path("sources").get(0).path("source").asText()).isEqualTo("cam2");
    }

    @Test
    void answers400ForAFrameIdThatIsNotANumber() throws Exception {
        HttpResponse<String> response = get(readOnlyBase + "/api/frame?id=banana");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asText())
                .isEqualTo("id must be a number");
    }

    // -----------------------------------------------------------------------
    // Images
    // -----------------------------------------------------------------------

    @Test
    void servesAFrameAsAnImmutableImage() throws Exception {
        store(10, "cam1", NOON);

        HttpResponse<byte[]> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(readOnlyBase + "/api/frames/10/image")).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(JPEG);
        assertThat(response.headers().firstValue("content-type")).contains("image/jpeg");
        // Immutable is true in the strict sense here: a frame id comes from a sequence and its bytes
        // never change, which is what makes stepping back and forth free after the first pass.
        assertThat(response.headers().firstValue("cache-control")).get()
                .asString().contains("immutable");
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
        assertThat(response.headers().firstValue("etag")).contains("\"frame-10\"");
    }

    @Test
    void answers304WhenTheBrowserAlreadyHasTheFrame() throws Exception {
        store(10, "cam1", NOON);

        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(readOnlyBase + "/api/frames/10/image"))
                        .header("If-None-Match", "\"frame-10\"").build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
    }

    /**
     * The hub stores whatever bytes it is pushed, so a payload that is really markup is reachable —
     * and it must come back as an attachment of unknown type, never as something the browser renders.
     */
    @Test
    void refusesToRenderAPayloadThatIsNotAnImage() throws Exception {
        storeBytes(10, "cam1", NOON, "<html><script>alert(1)</script>"
                .getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = get(readOnlyBase + "/api/frames/10/image");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .contains("application/octet-stream");
        assertThat(response.headers().firstValue("content-disposition")).get()
                .asString().contains("attachment");
        assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
    }

    @Test
    void answers404ForAFrameThatIsNoLongerStored() throws Exception {
        HttpResponse<String> response = get(readOnlyBase + "/api/frames/999/image");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // The list
    // -----------------------------------------------------------------------

    @Test
    void pagesTheFrameListNewestFirst() throws Exception {
        store(10, "cam1", NOON);
        store(11, "cam1", NOON.plusSeconds(1));
        store(12, "cam1", NOON.plusSeconds(2));

        JsonNode page = json(readOnlyBase + "/api/frames?source=cam1&limit=2");

        assertThat(page.path("returned").asInt()).isEqualTo(2);
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.path("nextBefore").asLong()).isEqualTo(11);
        assertThat(page.path("frames").get(0).path("frameId").asLong()).isEqualTo(12);
    }

    @Test
    void refusesAPageLargerThanTheCapRatherThanClampingIt() throws Exception {
        HttpResponse<String> response = get(readOnlyBase + "/api/frames?limit=5000");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asText()).contains("between 1 and");
    }

    // -----------------------------------------------------------------------
    // Uploads
    // -----------------------------------------------------------------------

    @Test
    void refusesAnUploadWhenNoHubIsConfigured() throws Exception {
        HttpResponse<String> response = post(
                readOnlyBase + "/api/uploads?source=cam1"
                        + "&from=2026-08-17T10:00:00Z&to=2026-08-17T10:05:00Z", "video-bytes");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(response.body()).path("error").asText())
                .contains("FRAME_VIEWER_HUB_URL");
    }

    @Test
    void reportsWhetherUploadsAreEnabled() throws Exception {
        assertThat(json(readOnlyBase + "/api/uploads").path("uploadsEnabled").asBoolean()).isFalse();
        assertThat(json(withUploadsBase + "/api/uploads").path("uploadsEnabled").asBoolean()).isTrue();
    }

    /** There is no default window: mis-dating an archive silently is worse than refusing it. */
    @Test
    void refusesAnUploadWithoutATimeWindow() throws Exception {
        HttpResponse<String> response =
                post(withUploadsBase + "/api/uploads?source=cam1", "video-bytes");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asText()).isEqualTo("from is required");
    }

    @Test
    void refusesAWindowThatEndsBeforeItStarts() throws Exception {
        HttpResponse<String> response = post(withUploadsBase + "/api/uploads?source=cam1"
                + "&from=2026-08-17T10:05:00Z&to=2026-08-17T10:00:00Z", "video-bytes");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asText())
                .isEqualTo("from must be before to");
    }

    @Test
    void refusesATimeThatIsNotAnInstant() throws Exception {
        HttpResponse<String> response = post(withUploadsBase + "/api/uploads?source=cam1"
                + "&from=yesterday&to=2026-08-17T10:00:00Z", "video-bytes");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asText()).contains("ISO-8601");
    }

    @Test
    void refusesAnUploadWithNoVideoInIt() throws Exception {
        HttpResponse<String> response = post(withUploadsBase + "/api/uploads?source=cam1"
                + "&from=2026-08-17T10:00:00Z&to=2026-08-17T10:05:00Z", "");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("error").asText()).contains("empty");
    }

    /**
     * The size limit is enforced while the body streams, so it has to come back as a status rather
     * than as a dropped connection — a caller that uploaded a 2 GB file deserves to be told why.
     */
    @Test
    void refusesAVideoLargerThanTheLimit() throws Exception {
        HttpResponse<String> response = post(withUploadsBase + "/api/uploads?source=cam1"
                        + "&from=2026-08-17T10:00:00Z&to=2026-08-17T10:05:00Z",
                "x".repeat(2 * 1024 * 1024));   // the test server's limit is 1 MiB

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(response.body()).path("error").asText())
                .contains("FRAME_VIEWER_UPLOAD_MAX_BYTES");
    }

    @Test
    void answers404ForAnUploadItHasNeverHeardOf() throws Exception {
        assertThat(get(withUploadsBase + "/api/uploads/nope").statusCode()).isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // Health
    // -----------------------------------------------------------------------

    @Test
    void reportsWhatItCanSeeOnHealth() throws Exception {
        store(10, "cam1", NOON);

        JsonNode health = json(readOnlyBase + "/health");

        assertThat(health.path("status").asText()).isEqualTo("healthy");
        assertThat(health.path("app").asText()).isEqualTo("jarvis-frame-viewer");
        assertThat(health.path("storedFrames").asInt()).isEqualTo(1);
        assertThat(health.path("sources").asInt()).isEqualTo(1);
        assertThat(health.path("uploadsEnabled").asBoolean()).isFalse();
        assertThat(health.path("authRequired").asBoolean()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static BackfillService backfill(String hubUrl) {
        return new BackfillService(
                new VideoFrames("ffmpeg", entry -> { }), hubUrl, 1.0, 25, entry -> { });
    }

    private static Undertow start(BackfillService backfill) {
        // No auth server: these tests are about the routes themselves, and the guard in front of
        // them has its own test class. ViewerAuth with no strategy answers every request.
        ViewerServer server = new ViewerServer(
                ViewerServerTest::connection, backfill,
                new ViewerAuth(null, java.time.Duration.ZERO, entry -> { }),
                200, 1024 * 1024, false, entry -> { });
        // Port 0: an ephemeral port, so two servers in one test class cannot collide and neither can
        // collide with whatever else is running on the machine. The bound port is read back below.
        return UndertowHttp.start("127.0.0.1", 0, server.handler());
    }

    private static String baseUrl(Undertow server) {
        InetSocketAddress address =
                (InetSocketAddress) server.getListenerInfo().get(0).getAddress();
        return "http://127.0.0.1:" + address.getPort();
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String url, String body) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(String url) throws Exception {
        HttpResponse<String> response = get(url);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void store(long id, String source, Instant capturedAt) throws SQLException {
        storeBytes(id, source, capturedAt, JPEG);
    }

    private static void storeBytes(long id, String source, Instant capturedAt, byte[] payload)
            throws SQLException {
        String sql = "INSERT INTO frame_events"
                + " (id, source, frame_index, sha256, captured_at, frame_bytes)"
                + " VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setString(2, source);
            statement.setLong(3, id);
            statement.setString(4, "sha-" + id);
            statement.setTimestamp(5, Timestamp.from(capturedAt));
            statement.setBytes(6, payload);
            statement.executeUpdate();
        }
    }
}

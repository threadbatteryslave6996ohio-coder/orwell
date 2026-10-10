package dev.orwell.bucket.viewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.testing.PostgresJdbcTest;
import dev.orwell.testing.http.JsonStubServer;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.Undertow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard in front of the viewer, over real HTTP against a real (stubbed) auth server.
 *
 * <p>Everything here is about what an unauthenticated caller gets, which is the part of an access
 * control that is worth testing: that the image route is guarded and not just the page, that a
 * refused sign-in does not set a cookie, and that the JSON routes answer the repo's shared 401
 * rather than a page of HTML the script cannot read.
 */
class ViewerServerAuthTest extends PostgresJdbcTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOON = Instant.parse("2026-08-17T12:00:00Z");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private static JsonStubServer authServer;
    private static Undertow viewer;
    private static String base;
    /** No redirect following: a 303 to the viewer is the assertion, not a step on the way to one. */
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void startServers() throws IOException {
        authServer = JsonStubServer.start();
        authServer.on("/def/login", exchange -> {
            String body = JsonStubServer.readBody(exchange);
            // "secret" is the only password this stub accepts, so a refused sign-in is testable.
            boolean accepted = body.contains("\"secret\":\"secret\"");
            respond(exchange, accepted ? 200 : 401, accepted
                    ? "{\"clientId\":\"watcher\",\"token\":\"token-456\"}"
                    : "{\"error\":\"no\"}");
        });
        authServer.on("/def/tokens/check", exchange -> {
            String body = JsonStubServer.readBody(exchange);
            boolean valid = body.contains("token-456");
            respond(exchange, 200, valid
                    ? "{\"valid\":true,\"clientId\":\"watcher\"}"
                    : "{\"valid\":false,\"clientId\":null}");
        });

        ViewerAuth auth = new ViewerAuth(
                new HttpAuthenticationStrategy(authServer.baseUrl(), entry -> { }),
                // No caching, so each assertion here is a real check rather than a remembered one.
                Duration.ZERO, entry -> { });
        ViewerServer server = new ViewerServer(
                ViewerServerAuthTest::connection,
                new BackfillService(new VideoFrames("ffmpeg", entry -> { }),
                        "http://127.0.0.1:1", 1.0, 25, entry -> { }),
                auth, 200, 1024 * 1024, false, entry -> { });
        viewer = UndertowHttp.start("127.0.0.1", 0, server.handler());
        base = "http://127.0.0.1:"
                + ((InetSocketAddress) viewer.getListenerInfo().get(0).getAddress()).getPort();
    }

    @AfterAll
    static void stopServers() {
        viewer.stop();
        authServer.close();
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
        store(10, "cam1", NOON);
    }

    // -----------------------------------------------------------------------
    // Nobody signed in
    // -----------------------------------------------------------------------

    @Test
    void offersTheSignInFormInsteadOfTheViewer() throws Exception {
        HttpResponse<String> response = get("/");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Sign in").contains("client id");
        assertThat(response.body()).doesNotContain("stored frames</span>");
        // A sign-in page must never be held in a cache, least of all a shared one.
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        // The form posts back here, which is the only reason form-action is not 'none'.
        assertThat(response.headers().firstValue("content-security-policy")).get()
                .asString().contains("form-action 'self'");
    }

    /**
     * The important one. Guarding the page and leaving the images open would mean the footage was
     * never actually protected — an image URL is guessable, they are sequential integers.
     */
    @Test
    void refusesTheFrameItselfAndNotJustThePage() throws Exception {
        assertThat(get("/api/frames/10/image").statusCode()).isEqualTo(401);
    }

    @Test
    void refusesEveryDataRouteWithTheSharedJsonContract() throws Exception {
        for (String path : List.of("/api/sources", "/api/frame?source=cam1",
                "/api/frames", "/api/frames/10/image", "/api/uploads", "/api/uploads/1", "/viewer.js")) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(401);
        }
        // JSON rather than the login page, so the script gets something it can read.
        assertThat(JSON.readTree(get("/api/sources").body()).path("error").asText())
                .isEqualTo("authentication required");
    }

    /** The write path is guarded too: reaching this port must not mean being able to add frames. */
    @Test
    void refusesAnUploadFromAnUnauthenticatedCaller() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + "/api/uploads?source=cam1"
                                + "&from=2026-08-17T10:00:00Z&to=2026-08-17T10:05:00Z"))
                        .POST(HttpRequest.BodyPublishers.ofString("video")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /** The health check has to work without credentials, and says whether anything is guarding. */
    @Test
    void leavesHealthOpenAndReportsThatAuthIsOn() throws Exception {
        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("authRequired").asBoolean()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Signing in
    // -----------------------------------------------------------------------

    @Test
    void signsInAndSetsASessionCookieThatOpensTheViewer() throws Exception {
        HttpResponse<String> login = login("watcher", "secret");

        assertThat(login.statusCode()).isEqualTo(303);
        assertThat(login.headers().firstValue("location")).contains("./");
        String cookie = login.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains(ViewerAuth.COOKIE)
                .contains("HttpOnly")          // not readable by script, so XSS cannot lift it
                .contains("SameSite=Strict");  // not sent on a cross-site request
        assertThat(cookie).doesNotContain("Secure");   // secureCookies=false in this test

        String session = cookie.substring(0, cookie.indexOf(';'));
        assertThat(withCookie("/", session).body()).contains("stored frames");
        assertThat(withCookie("/api/frames/10/image", session).statusCode()).isEqualTo(200);
    }

    @Test
    void refusesTheSignInWithoutSettingACookie() throws Exception {
        HttpResponse<String> login = login("watcher", "wrong");

        assertThat(login.statusCode()).isEqualTo(401);
        assertThat(login.headers().firstValue("set-cookie")).isEmpty();
        assertThat(login.body()).contains("were not accepted");
    }

    @Test
    void asksForBothFieldsWhenOneIsMissing() throws Exception {
        HttpResponse<String> login = post("/login", "clientId=watcher");

        assertThat(login.statusCode()).isEqualTo(400);
        assertThat(login.body()).contains("are required");
        assertThat(login.headers().firstValue("set-cookie")).isEmpty();
    }

    @Test
    void ignoresACookieTheAuthServerNoLongerAccepts() throws Exception {
        String stale = ViewerAuth.COOKIE + "=" + ViewerAuth.session("watcher", "revoked-token");

        assertThat(withCookie("/api/sources", stale).statusCode()).isEqualTo(401);
    }

    @Test
    void clearsTheCookieOnSignOut() throws Exception {
        HttpResponse<String> logout = post("/logout", "");

        assertThat(logout.statusCode()).isEqualTo(303);
        String cookie = logout.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains(ViewerAuth.COOKIE + "=");
        // Undertow renders a zero max-age as an Expires in the past rather than `Max-Age=0`. Either
        // deletes the cookie; asserting on the expiry is asserting on what the browser acts upon,
        // and an empty value on its own would leave the cookie in place until the session ended.
        assertThat(cookie).contains("Expires=Thu, 01-Jan-1970");
        // Signing out must not merely blank the value while leaving a live cookie behind.
        assertThat(withCookie("/api/sources", ViewerAuth.COOKIE + "=").statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // Header credentials, for scripts
    // -----------------------------------------------------------------------

    /** So a backfill from curl needs no session — the repo's usual service-to-service headers. */
    @Test
    void acceptsTheHeaderCredentialsEveryOtherServiceUses() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sources"))
                        .header("X-Client-Id", "watcher")
                        .header("Authorization", "Bearer token-456")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("success").asBoolean()).isTrue();
    }

    @Test
    void refusesAHeaderTokenTheAuthServerRejects() throws Exception {
        HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + "/api/sources"))
                        .header("X-Client-Id", "watcher")
                        .header("Authorization", "Bearer not-the-token")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> withCookie(String path, String cookie) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String form) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> login(String clientId, String secret) throws Exception {
        return post("/login", "clientId=" + clientId + "&secret=" + secret);
    }

    private static void store(long id, String source, Instant capturedAt) throws SQLException {
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
            statement.setBytes(6, JPEG);
            statement.executeUpdate();
        }
    }
}

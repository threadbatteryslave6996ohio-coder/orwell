package dev.orwell.bucket.viewer;

import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the viewer accepts as a sign-in, against a real auth server on a loopback port.
 *
 * <p>Stubbed rather than mocked because the subject is what actually goes over the wire — the route
 * the workspace produces, the credentials in the body, and how many times the token is checked.
 */
class ViewerAuthTest {
    private static final String LOGIN = "/def/login";
    private static final String CHECK = "/def/tokens/check";

    // -----------------------------------------------------------------------
    // Signing in
    // -----------------------------------------------------------------------

    @Test
    void exchangesCredentialsForATokenAndPacksItIntoASession() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            AtomicReference<String> sent = auth.stub(LOGIN, 200,
                    "{\"clientId\":\"watcher\",\"token\":\"token-456\"}");

            Optional<String> session = viewerAuth(auth, Duration.ZERO).login("watcher", "secret");

            assertThat(session).isPresent();
            // The credentials really went to the auth server; nothing is checked locally.
            assertThat(sent.get()).contains("\"clientId\":\"watcher\"").contains("\"secret\"");
            // base64url(clientId) . token — the same packing object-storage-proxy's panel uses.
            assertThat(session.get()).isEqualTo(
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString("watcher".getBytes(StandardCharsets.UTF_8))
                            + ".token-456");
        }
    }

    @Test
    void refusesCredentialsTheAuthServerDoesNotAccept() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            auth.stub(LOGIN, 401, "{\"error\":\"nope\"}");

            assertThat(viewerAuth(auth, Duration.ZERO).login("watcher", "wrong")).isEmpty();
        }
    }

    /** A 200 with no token in it is the auth server breaking its own contract; not a sign-in. */
    @Test
    void refusesALoginResponseThatCarriesNoToken() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            auth.stub(LOGIN, 200, "{\"clientId\":\"watcher\"}");

            assertThat(viewerAuth(auth, Duration.ZERO).login("watcher", "secret")).isEmpty();
        }
    }

    /**
     * An unreachable auth server is a failed sign-in, not a 500 — and deliberately gives the same
     * answer as wrong credentials, so the form cannot be used to tell one from the other.
     */
    @Test
    void refusesTheSignInWhenTheAuthServerCannotBeReached() {
        ViewerAuth auth = new ViewerAuth(
                new HttpAuthenticationStrategy("http://127.0.0.1:1", entry -> { }),
                Duration.ZERO, entry -> { });

        assertThat(auth.login("watcher", "secret")).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Checking a session
    // -----------------------------------------------------------------------

    @Test
    void acceptsASessionTheAuthServerStillVouchesFor() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            AtomicReference<String> sent =
                    auth.stub(CHECK, 200, "{\"valid\":true,\"clientId\":\"watcher\"}");

            assertThat(viewerAuth(auth, Duration.ZERO).fromSession(session("watcher", "token-456")))
                    .contains("watcher");
            assertThat(sent.get()).contains("token-456");
        }
    }

    @Test
    void rejectsATokenTheAuthServerNoLongerAccepts() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            auth.stub(CHECK, 200, "{\"valid\":false,\"clientId\":null}");

            assertThat(viewerAuth(auth, Duration.ZERO).fromSession(session("watcher", "revoked")))
                    .isEmpty();
        }
    }

    /** Fails closed: while auth cannot be verified, no footage is served. */
    @Test
    void rejectsEverythingWhileTheAuthServerIsUnreachable() {
        ViewerAuth auth = new ViewerAuth(
                new HttpAuthenticationStrategy("http://127.0.0.1:1", entry -> { }),
                Duration.ofSeconds(30), entry -> { });

        assertThat(auth.fromSession(session("watcher", "token-456"))).isEmpty();
    }

    @Test
    void rejectsACookieThatIsNotASession() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            auth.stub(CHECK, 200, "{\"valid\":true,\"clientId\":\"watcher\"}");
            ViewerAuth viewer = viewerAuth(auth, Duration.ZERO);

            assertThat(viewer.fromSession(null)).isEmpty();
            assertThat(viewer.fromSession("")).isEmpty();
            assertThat(viewer.fromSession("no-separator")).isEmpty();
            assertThat(viewer.fromSession("!!not-base64!!.token")).isEmpty();
            assertThat(viewer.fromSession(".token")).isEmpty();
        }
    }

    /** A token containing a dot survives: only the first separator splits. */
    @Test
    void keepsATokenThatContainsTheSeparator() throws Exception {
        try (JsonStubServer auth = JsonStubServer.start()) {
            AtomicReference<String> sent =
                    auth.stub(CHECK, 200, "{\"valid\":true,\"clientId\":\"watcher\"}");

            assertThat(viewerAuth(auth, Duration.ZERO).fromSession(session("watcher", "a.b.c")))
                    .contains("watcher");
            assertThat(sent.get()).contains("a.b.c");
        }
    }

    // -----------------------------------------------------------------------
    // The cache
    // -----------------------------------------------------------------------

    /**
     * One step through the history is three requests, so an uncached check would put three blocking
     * POSTs to the auth server on the path of every arrow-key press.
     */
    @Test
    void asksTheAuthServerOnceForATokenItHasJustAccepted() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (JsonStubServer auth = counting(checks, "{\"valid\":true,\"clientId\":\"watcher\"}")) {
            ViewerAuth viewer = viewerAuth(auth, Duration.ofSeconds(30));

            assertThat(viewer.fromSession(session("watcher", "token-456"))).contains("watcher");
            assertThat(viewer.fromSession(session("watcher", "token-456"))).contains("watcher");
            assertThat(viewer.fromSession(session("watcher", "token-456"))).contains("watcher");

            assertThat(checks.get()).isEqualTo(1);
        }
    }

    /** A refusal is never remembered, so a wrong token gets no free pass from a cache. */
    @Test
    void asksAgainEveryTimeForATokenItRefused() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (JsonStubServer auth = counting(checks, "{\"valid\":false,\"clientId\":null}")) {
            ViewerAuth viewer = viewerAuth(auth, Duration.ofSeconds(30));

            assertThat(viewer.fromSession(session("watcher", "bad"))).isEmpty();
            assertThat(viewer.fromSession(session("watcher", "bad"))).isEmpty();

            assertThat(checks.get()).isEqualTo(2);
        }
    }

    @Test
    void asksEveryTimeWhenTheCacheIsTurnedOff() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (JsonStubServer auth = counting(checks, "{\"valid\":true,\"clientId\":\"watcher\"}")) {
            ViewerAuth viewer = viewerAuth(auth, Duration.ZERO);

            viewer.fromSession(session("watcher", "token-456"));
            viewer.fromSession(session("watcher", "token-456"));

            assertThat(checks.get()).isEqualTo(2);
        }
    }

    /** Cached per token, so one client's accepted session does not vouch for another's. */
    @Test
    void doesNotLetOneAcceptedTokenVouchForAnother() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try (JsonStubServer auth = counting(checks, "{\"valid\":true,\"clientId\":\"watcher\"}")) {
            ViewerAuth viewer = viewerAuth(auth, Duration.ofSeconds(30));

            viewer.fromSession(session("watcher", "token-a"));
            viewer.fromSession(session("watcher", "token-b"));

            assertThat(checks.get()).isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // No auth server configured
    // -----------------------------------------------------------------------

    @Test
    void isOffWhenThereIsNoAuthServer() {
        ViewerAuth viewer = new ViewerAuth(null, Duration.ZERO, entry -> { });

        assertThat(viewer.enabled()).isFalse();
        assertThat(viewer.login("watcher", "secret")).isEmpty();
    }

    private static ViewerAuth viewerAuth(JsonStubServer auth, Duration cacheTtl) {
        return new ViewerAuth(
                new HttpAuthenticationStrategy(auth.baseUrl(), entry -> { }), cacheTtl,
                entry -> { });
    }

    private static JsonStubServer counting(AtomicInteger calls, String body) throws Exception {
        JsonStubServer server = JsonStubServer.start();
        server.on(CHECK, exchange -> {
            calls.incrementAndGet();
            JsonStubServer.readBody(exchange);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        return server;
    }

    private static String session(String clientId, String token) {
        return ViewerAuth.session(clientId, token);
    }
}

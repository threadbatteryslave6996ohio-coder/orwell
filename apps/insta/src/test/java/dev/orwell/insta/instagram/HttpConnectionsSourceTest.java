package dev.orwell.insta.instagram;

import com.sun.net.httpserver.HttpExchange;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.logging.Logger;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.orwell.testing.http.JsonStubServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two sources that are HTTP endpoints rather than Apify actors, against a real socket — so the
 * URLs built, the headers sent, and what is made of a response are all exercised for real.
 *
 * <p>The cases that matter most here are the refusals. These sources sit in a failover chain, and a
 * chain is only safe if "this one cannot answer" is never confused with "this account has no
 * followers": an empty list is what {@code FollowWriter} reads as everyone leaving at once.
 */
class HttpConnectionsSourceTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private JsonStubServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"accounts\":[]}";

    @BeforeEach
    void start() throws IOException {
        server = JsonStubServer.start();
        server.on("/", this::handle);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    // ─────────────────────────────────────────────────────────── self-hosted

    @Test
    void selfHostedBuildsTheDocumentedUrlAndSendsItsToken() {
        body = """
                {"accounts":[{"id":"1","username":"alice","full_name":"Alice"}],
                 "nextCursor":"page-2"}""";

        ConnectionsSource.Result result =
                selfHosted("s3cret", true).fetch("nasa", ConnectionType.FOLLOWERS, 500, null);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/connections");
            assertThat(request.query())
                    .isEqualTo("username=nasa&type=followers&limit=500");
            assertThat(request.header("Authorization")).isEqualTo("Bearer s3cret");
        });
        assertThat(result.items()).hasSize(1);
        assertThat(InstagramAccount.from(result.items().get(0)).username()).isEqualTo("alice");
        assertThat(result.nextToken()).isEqualTo("page-2");
    }

    @Test
    void selfHostedSendsNoAuthorizationWhenNoTokenIsSet() {
        selfHosted("", true).fetch("nasa", ConnectionType.FOLLOWING, 50, "abc");

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.query()).isEqualTo("username=nasa&type=following&limit=50&cursor=abc");
            assertThat(request.header("Authorization")).isNull();
        });
    }

    /** Ours, so it reads both directions — unlike most of the marketplace actors. */
    @Test
    void selfHostedReadsBothDirections() {
        assertThat(selfHosted("", true).supports(ConnectionType.FOLLOWERS)).isTrue();
        assertThat(selfHosted("", true).supports(ConnectionType.FOLLOWING)).isTrue();
    }

    /**
     * Declaring no pagination must suppress the token, not merely ignore it. A source that cannot
     * prove it reached the end of a list must never be able to end one.
     */
    @Test
    void selfHostedWithoutPaginationNeverReportsACursor() {
        body = "{\"accounts\":[{\"username\":\"alice\"}],\"nextCursor\":\"page-2\"}";

        ConnectionsSource.Result result =
                selfHosted("", false).fetch("nasa", ConnectionType.FOLLOWERS, 500, null);

        assertThat(result.nextToken()).isNull();
    }

    // ─────────────────────────────────────────────────────────── rapidapi

    @Test
    void rapidApiSendsBothGatewayHeadersAndTheConfiguredParameterNames() {
        body = "{\"data\":{\"items\":[{\"username\":\"bob\"}]}}";

        ConnectionsSource.Result result =
                rapidApi(false, false).fetch("nasa", ConnectionType.FOLLOWERS, 100, null);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/v1/followers");
            assertThat(request.query()).isEqualTo("username_or_id_or_url=nasa&count=100");
            assertThat(request.header("X-RapidAPI-Key")).isEqualTo("rapid-key");
            // Sent as well as being the hostname, and always without the scheme: the gateway
            // routes on this header and rejects one carrying "https://".
            assertThat(request.header("X-RapidAPI-Host"))
                    .isEqualTo(server.baseUrl().replace("http://", ""));
        });
        assertThat(result.items()).hasSize(1);
    }

    /** Both default off, so a vendor that sells only followers is skipped for the other direction. */
    @Test
    void rapidApiDefaultsToFollowersOnlyAndNoPagination() {
        RapidApiConnectionsSource source = rapidApi(false, false);

        assertThat(source.supports(ConnectionType.FOLLOWERS)).isTrue();
        assertThat(source.supports(ConnectionType.FOLLOWING)).isFalse();
        assertThat(source.supportsCursor()).isFalse();
    }

    @Test
    void rapidApiUsesTheFollowingPathWhenItIsConfiguredToReadOne() {
        body = "{\"items\":[]}";

        rapidApi(true, true).fetch("nasa", ConnectionType.FOLLOWING, 100, "tok");

        assertThat(requests).singleElement().satisfies(request ->
                assertThat(request.path()).isEqualTo("/v1/following"));
        assertThat(requests.get(0).query()).contains("pagination_token=tok");
    }

    // ─────────────────────────────────────────────────────────── shared behaviour

    @Test
    void findsTheAccountsArrayWhateverItIsWrappedIn() {
        for (String shape : List.of(
                "[{\"username\":\"a\"}]",
                "{\"accounts\":[{\"username\":\"a\"}]}",
                "{\"items\":[{\"username\":\"a\"}]}",
                "{\"users\":[{\"username\":\"a\"}]}",
                "{\"data\":[{\"username\":\"a\"}]}",
                "{\"data\":{\"items\":[{\"username\":\"a\"}]}}")) {
            body = shape;
            assertThat(selfHosted("", true).fetch("nasa", ConnectionType.FOLLOWERS, 10, null)
                    .items()).as(shape).hasSize(1);
        }
    }

    /**
     * The single most important case in this file. A response we cannot find accounts in is a
     * failure, never an empty page — the walk must stop rather than hand {@code sync} an empty list
     * that reads as every follower leaving at once.
     */
    @Test
    void anUnrecognisableResponseIsAFailureNotAnEmptyPage() {
        body = "{\"message\":\"you are not subscribed to this API\"}";

        assertThatThrownBy(() ->
                selfHosted("", true).fetch("nasa", ConnectionType.FOLLOWERS, 10, null))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("no recognisable accounts array")
                .extracting(failure -> ((ApifyException) failure).kind())
                .isEqualTo(ApifyException.Kind.UNAVAILABLE);
    }

    /** A genuinely empty list still has to come through as one. */
    @Test
    void anEmptyArrayIsStillAnEmptyPage() {
        body = "{\"accounts\":[]}";

        ConnectionsSource.Result result =
                selfHosted("", true).fetch("nasa", ConnectionType.FOLLOWERS, 10, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextToken()).isNull();
    }

    /**
     * Statuses map to the kinds the chain acts on. The three in {@code WORTH_FAILING_OVER} let the
     * next source try; a 400 must not, because every source would reject it identically.
     */
    @Test
    void mapsStatusesToKindsTheChainCanActOn() {
        assertThat(kindFor(429)).isEqualTo(ApifyException.Kind.RATE_LIMITED);
        assertThat(kindFor(402)).isEqualTo(ApifyException.Kind.OUT_OF_CREDIT);
        assertThat(kindFor(401)).isEqualTo(ApifyException.Kind.UNAVAILABLE);
        assertThat(kindFor(403)).isEqualTo(ApifyException.Kind.UNAVAILABLE);
        assertThat(kindFor(503)).isEqualTo(ApifyException.Kind.UNAVAILABLE);
        assertThat(kindFor(504)).isEqualTo(ApifyException.Kind.TIMED_OUT);
        assertThat(kindFor(400)).isEqualTo(ApifyException.Kind.TOKEN_REJECTED);
    }

    @Test
    void refusesToBuildWithoutItsRequiredConfiguration() {
        assertThatThrownBy(() -> new SelfHostedConnectionsSource(
                "", null, true, Duration.ofSeconds(5), NO_OP_LOGGER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSTA_SELFHOSTED_URL");

        assertThatThrownBy(() -> new RapidApiConnectionsSource(
                RapidApiConnectionsSource.Settings.of(
                        "host", "", null, null, null, null, null, false, false, 5),
                NO_OP_LOGGER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSTA_RAPIDAPI_KEY");
    }

    // ────────────────────────────────────────────────────────── helpers

    private ApifyException.Kind kindFor(int code) {
        status = code;
        body = "{\"error\":\"no\"}";
        try {
            selfHosted("", true).fetch("nasa", ConnectionType.FOLLOWERS, 10, null);
            throw new AssertionError("HTTP " + code + " should have failed the fetch.");
        } catch (ApifyException failure) {
            return failure.kind();
        } finally {
            status = 200;
        }
    }

    private SelfHostedConnectionsSource selfHosted(String token, boolean paginates) {
        return new SelfHostedConnectionsSource(
                server.baseUrl(), token, paginates, Duration.ofSeconds(5), NO_OP_LOGGER);
    }

    private RapidApiConnectionsSource rapidApi(boolean paginates, boolean readsFollowing) {
        // A host carrying a scheme is taken as given, which is how the stub is reachable at all —
        // a bare hostname would get https and there is no TLS on this socket.
        return new RapidApiConnectionsSource(
                RapidApiConnectionsSource.Settings.of(
                        server.baseUrl(), "rapid-key", null, null, null, null, null,
                        paginates, readsFollowing, 5),
                NO_OP_LOGGER);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new Request(
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                Map.copyOf(exchange.getRequestHeaders().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                                entry -> entry.getValue().get(0))))));
        respond(exchange, status, body);
    }

    private record Request(String path, String query, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }
}

package dev.orwell.insta.scraper;

import com.sun.net.httpserver.HttpExchange;
import dev.orwell.logging.Logger;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static dev.orwell.testing.http.JsonStubServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Instagram side, against a stub standing in for {@code www.instagram.com} — so the URLs built,
 * the session headers sent, and what is made of each kind of refusal are all exercised for real.
 *
 * <p>The refusals carry the weight. Every one of them has to stay distinguishable from an empty
 * list, because three services downstream an empty list is recorded as every follower leaving at
 * once.
 */
class InstagramWebClientTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private JsonStubServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = JsonStubServer.start();
        server.on("/", this::handle);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void sendsTheSessionCookiesAndTheAppIdHeader() throws Exception {
        body = "{\"data\":{\"user\":{\"id\":\"528817151\"}}}";

        String id = client().userId("nasa");

        assertThat(id).isEqualTo("528817151");
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/v1/users/web_profile_info/");
            assertThat(request.query()).isEqualTo("username=nasa");
            assertThat(request.header("cookie"))
                    .contains("sessionid=abc123")
                    .contains("ds_user_id=42")
                    .contains("csrftoken=csrf")
                    .contains("mid=xyz");
            // Without this the endpoints answer 400.
            assertThat(request.header("x-ig-app-id")).isEqualTo("936619743392459");
            assertThat(request.header("x-csrftoken")).isEqualTo("csrf");
        });
    }

    @Test
    void readsAPageAndItsCursor() throws Exception {
        body = """
                {"users":[
                   {"pk":"1","username":"alice","full_name":"Alice","is_private":true,
                    "is_verified":false,"profile_pic_url":"https://cdn/a.jpg"},
                   {"pk":"2","username":"bob"}],
                 "next_max_id":"QVFB"}""";

        InstagramWebClient.Page page =
                client().connections("528817151", InstagramWebClient.Direction.FOLLOWERS, null);

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/v1/friendships/528817151/followers/");
            assertThat(request.query()).isEqualTo("count=50");
        });
        assertThat(page.accounts()).extracting(InstagramWebClient.Account::username)
                .containsExactly("alice", "bob");
        assertThat(page.accounts().get(0).id()).isEqualTo("1");
        assertThat(page.accounts().get(0).isPrivate()).isTrue();
        assertThat(page.nextMaxId()).isEqualTo("QVFB");
    }

    /** An absent next_max_id is the only thing that may end a list. */
    @Test
    void reportsNoCursorWhenInstagramSaysTheListIsExhausted() throws Exception {
        body = "{\"users\":[{\"pk\":\"1\",\"username\":\"alice\"}]}";

        InstagramWebClient.Page page =
                client().connections("1", InstagramWebClient.Direction.FOLLOWING, "QVFB");

        assertThat(page.nextMaxId()).isNull();
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/api/v1/friendships/1/following/");
            assertThat(request.query()).isEqualTo("count=50&max_id=QVFB");
        });
    }

    /** A genuinely empty list must still come through as one. */
    @Test
    void anEmptyUsersArrayIsAnEmptyPage() throws Exception {
        body = "{\"users\":[]}";

        InstagramWebClient.Page page =
                client().connections("1", InstagramWebClient.Direction.FOLLOWERS, null);

        assertThat(page.accounts()).isEmpty();
        assertThat(page.nextMaxId()).isNull();
    }

    /**
     * The case this whole class is shaped around: a response with no users array is a failure, not
     * an empty page. 502 so insta fails over to a paid actor rather than recording a mass unfollow.
     */
    @Test
    void aResponseWithNoUsersArrayIsAFailureNotAnEmptyPage() {
        body = "{\"status\":\"fail\",\"message\":\"checkpoint_required\"}";

        assertThatThrownBy(() ->
                client().connections("1", InstagramWebClient.Direction.FOLLOWERS, null))
                .isInstanceOf(InstagramWebClient.ScrapeException.class)
                .hasMessageContaining("No users array")
                .extracting(failure -> ((InstagramWebClient.ScrapeException) failure).status())
                .isEqualTo(502);
    }

    /** HTML instead of JSON is a checkpoint interstitial, which means the session is done. */
    @Test
    void nonJsonReadsAsACheckpointedSession() {
        body = "<!DOCTYPE html><html><body>Suspicious login attempt</body></html>";

        assertThatThrownBy(() -> client().userId("nasa"))
                .isInstanceOf(InstagramWebClient.ScrapeException.class)
                .hasMessageContaining("checkpointed")
                .extracting(failure -> ((InstagramWebClient.ScrapeException) failure).status())
                .isEqualTo(401);
    }

    /**
     * A dead session answers 302 to the login page. Redirects are deliberately not followed — that
     * would turn it into a 200 of HTML and hide what actually happened.
     */
    @Test
    void anExpiredSessionIsNotFollowedToTheLoginPage() {
        status = 302;
        body = "";

        assertThatThrownBy(() ->
                client().connections("1", InstagramWebClient.Direction.FOLLOWERS, null))
                .isInstanceOf(InstagramWebClient.ScrapeException.class)
                .hasMessageContaining("may have expired")
                .extracting(failure -> ((InstagramWebClient.ScrapeException) failure).status())
                .isEqualTo(401);
    }

    @Test
    void mapsInstagramsOwnThrottleToA429WithARetryHint() {
        status = 429;
        body = "{\"message\":\"Please wait a few minutes\"}";

        assertThatThrownBy(() ->
                client().connections("1", InstagramWebClient.Direction.FOLLOWERS, null))
                .isInstanceOf(InstagramWebClient.ScrapeException.class)
                .satisfies(failure -> {
                    InstagramWebClient.ScrapeException scrape =
                            (InstagramWebClient.ScrapeException) failure;
                    assertThat(scrape.status()).isEqualTo(429);
                    assertThat(scrape.retryAfterSeconds()).isPositive();
                });
    }

    @Test
    void aMissingAccountIsA404RatherThanAnEmptyList() {
        status = 404;
        body = "{}";

        assertThatThrownBy(() -> client().userId("nope"))
                .isInstanceOf(InstagramWebClient.ScrapeException.class)
                .extracting(failure -> ((InstagramWebClient.ScrapeException) failure).status())
                .isEqualTo(404);
    }

    /** The budget refuses rather than blocking, so the endpoint can answer 429 immediately. */
    @Test
    void aSpentHourlyBudgetIsA429BeforeAnyRequestIsMade() {
        body = "{\"users\":[]}";
        InstagramWebClient client = new InstagramWebClient(
                server.baseUrl(), session(), new RequestBudget(0, 1), 50, NO_OP_LOGGER);

        assertThatThrownBy(() -> {
            client.connections("1", InstagramWebClient.Direction.FOLLOWERS, null);
            client.connections("1", InstagramWebClient.Direction.FOLLOWERS, null);
        }).isInstanceOf(InstagramWebClient.ScrapeException.class)
                .extracting(failure -> ((InstagramWebClient.ScrapeException) failure).status())
                .isEqualTo(429);

        // The second call never reached the network.
        assertThat(requests).hasSize(1);
    }

    // ────────────────────────────────────────────────────────── helpers

    private InstagramWebClient client() {
        return new InstagramWebClient(
                server.baseUrl(), session(), new RequestBudget(0, 1000), 50, NO_OP_LOGGER);
    }

    private static InstagramSession session() {
        return new InstagramSession(
                "abc123", "42", "csrf", "mid=xyz; ig_did=DID",
                "test-agent", "936619743392459");
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new Request(
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                entry -> entry.getKey().toLowerCase(Locale.ROOT),
                                entry -> entry.getValue().get(0)))));
        respond(exchange, status, body);
    }

    private record Request(String path, String query, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }
}

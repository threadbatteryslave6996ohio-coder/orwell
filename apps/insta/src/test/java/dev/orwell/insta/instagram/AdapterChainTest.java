package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.insta.support.ApifyStubServer;
import dev.orwell.insta.support.InMemoryScrapeCache;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Falling through to the next actor when one is spent.
 *
 * <p>The stub answers whichever actor the chain addressed, so these tests are about the chain's
 * decisions — which adapter is asked, in what order, what is discarded on the way — rather than
 * about any particular actor's wire format.
 */
class AdapterChainTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    private ApifyStubServer apify;
    private InMemoryScrapeCache cache;

    @BeforeEach
    void start() throws IOException {
        apify = ApifyStubServer.start();
        cache = new InMemoryScrapeCache();
    }

    @AfterEach
    void stop() {
        apify.close();
    }

    @Test
    void usesTheFirstAdapterThatSupportsTheDirection() {
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"alice\"}]");

        service(List.of(adapter("first", true, true), adapter("second", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null);

        assertThat(apify.runStart().path()).contains("first");
    }

    /** The whole point: a spent quota moves the walk onto the next actor instead of failing. */
    @Test
    void fallsThroughToTheNextAdapterWhenOneIsExhausted() {
        ConnectionsAdapter spent = refusing("spent", ApifyException.Kind.RATE_LIMITED);
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"alice\"}]");

        ConnectionsPage page = service(List.of(spent, adapter("backup", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null);

        assertThat(page.accounts()).extracting(InstagramAccount::username).containsExactly("alice");
        assertThat(apify.runStart().path()).contains("backup");
    }

    @Test
    void alsoFallsThroughOnAnExhaustedBalanceOrABrokenActor() {
        for (ApifyException.Kind kind : List.of(
                ApifyException.Kind.OUT_OF_CREDIT, ApifyException.Kind.UNAVAILABLE)) {
            apify.responds(200, "[{\"id\":\"1\",\"username\":\"alice\"}]");
            assertThat(service(List.of(refusing("spent", kind), adapter("backup", true, true)))
                    .connections("nasa", ConnectionType.FOLLOWERS, 50, null).accounts()).hasSize(1);
        }
    }

    /**
     * A timeout usually means the account is large. Handing the same account to another actor
     * spends a second lot of money to fail the same way, so the chain stops.
     */
    @Test
    void doesNotFailOverOnATimeout() {
        assertThatThrownBy(() -> service(List.of(
                refusing("slow", ApifyException.Kind.TIMED_OUT), adapter("backup", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.TIMED_OUT));
    }

    /** When the last adapter refuses too, the caller gets the failure rather than an empty list. */
    @Test
    void reportsTheFailureWhenEveryAdapterIsExhausted() {
        assertThatThrownBy(() -> service(List.of(
                refusing("one", ApifyException.Kind.RATE_LIMITED),
                refusing("two", ApifyException.Kind.RATE_LIMITED)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null))
                .isInstanceOfSatisfying(ApifyException.class, exception ->
                        assertThat(exception.kind()).isEqualTo(ApifyException.Kind.RATE_LIMITED));
    }

    /** Most follower scrapers cannot read a following list; the chain must not ask them to. */
    @Test
    void skipsAdaptersThatCannotScrapeTheDirection() {
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"alice\"}]");

        service(List.of(followersOnly("followers-only"), adapter("both", true, true)))
                .connections("nasa", ConnectionType.FOLLOWING, 50, null);

        assertThat(apify.runStart().path()).contains("both");
    }

    @Test
    void failsClearlyWhenNoAdapterCanScrapeTheDirection() {
        assertThatThrownBy(() -> service(List.of(followersOnly("followers-only")))
                .connections("nasa", ConnectionType.FOLLOWING, 50, null))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("No configured actor can scrape following");
    }

    // ────────────────────────────────────────────────────────── completeness

    /**
     * An actor with no pagination that returns exactly what was asked for has said nothing about
     * whether more exist — and a walk that cannot prove it saw everything must never retire.
     */
    @Test
    void doesNotCallAFullPageTheEndOfTheList() {
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"a\"},{\"id\":\"2\",\"username\":\"b\"}]");

        ConnectionsPage page = service(List.of(adapter("nopaging", true, false)))
                .connections("nasa", ConnectionType.FOLLOWERS, 2, null);

        assertThat(page.accounts()).hasSize(2);
        assertThat(page.endOfList()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    /**
     * Not even a short page. Observed live: datadoping answered a limit of 50 with 48 accounts for
     * an account that has 441, so "fewer than asked for" means the actor stopped early, not that
     * the list ended. Without pagination there is no honest way to tell, so it never claims to.
     */
    @Test
    void neverClaimsTheEndOfTheListWithoutPagination() {
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"a\"}]");

        assertThat(service(List.of(adapter("nopaging", true, false)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null).endOfList()).isFalse();
    }

    // ────────────────────────────────────────────────────────── cursors

    /** A continuation token means nothing to an actor that did not issue it. */
    @Test
    void resumesWithTheAdapterThatIssuedTheCursor() {
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"T2\"}]}");
        String cursor = service(List.of(adapter("issuer", true, true), adapter("other", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null).nextCursor();
        assertThat(cursor).isNotNull();

        // The issuer is now second in preference; the cursor still has to go back to it.
        service(List.of(adapter("other", true, true), adapter("issuer", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, cursor);

        assertThat(apify.runStart().path()).contains("issuer");
    }

    @Test
    void refusesACursorWhoseAdapterIsNoLongerConfigured() {
        apify.responds(200, "[{\"id\":\"1\",\"username\":\"alice\"}]");
        apify.outputs("{\"continuations\":[{\"nextContinuationToken\":\"T2\"}]}");
        String cursor = service(List.of(adapter("issuer", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, null).nextCursor();

        assertThatThrownBy(() -> service(List.of(adapter("other", true, true)))
                .connections("nasa", ConnectionType.FOLLOWERS, 50, cursor))
                .isInstanceOf(ApifyException.class)
                .hasMessageContaining("no longer configured");
    }

    // ────────────────────────────────────────────────────────── helpers

    private InstagramService service(List<ConnectionsAdapter> adapters) {
        ApifyClient client = new ApifyClient(apify.baseUrl(), "test-token", 120, NO_OP_LOGGER);
        return new InstagramService(
                client, cache, "apify/profile", adapters, 100, 500, NO_OP_LOGGER);
    }

    /** An adapter whose actor id is its name, so the stub's recorded path identifies it. */
    private static ConnectionsAdapter adapter(String name, boolean both, boolean cursor) {
        return new TestAdapter(name, both, cursor, null);
    }

    private static ConnectionsAdapter followersOnly(String name) {
        return new TestAdapter(name, false, true, null);
    }

    private static ConnectionsAdapter refusing(String name, ApifyException.Kind kind) {
        return new TestAdapter(name, true, true, kind);
    }

    private record TestAdapter(
            String name, boolean both, boolean cursor, ApifyException.Kind refuseWith)
            implements ConnectionsAdapter {

        @Override
        public String actorId() {
            return "test/" + name;
        }

        @Override
        public boolean supports(ConnectionType type) {
            return both || type == ConnectionType.FOLLOWERS;
        }

        @Override
        public boolean supportsCursor() {
            return cursor;
        }

        @Override
        public Map<String, Object> input(
                String username, ConnectionType type, int limit, String token) {
            return Map.of("username", username, "limit", limit);
        }

        @Override
        public void verify(JsonNode output) {
            if (refuseWith != null) {
                throw new ApifyException(name + " is exhausted", refuseWith);
            }
        }

        @Override
        public String nextToken(JsonNode output) {
            return ConnectionCursor.nextTokenIn(output);
        }
    }
}

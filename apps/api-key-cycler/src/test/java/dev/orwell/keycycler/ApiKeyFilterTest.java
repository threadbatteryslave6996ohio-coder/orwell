package dev.orwell.keycycler;

import dev.orwell.logging.LogEntry;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFilterTest {
    private static final Set<Integer> ROTATE_ON = Set.of(429, 401, 403);

    private final List<LogEntry> logged = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = logged::add;

    private ApiKeyFilter filter(ApiKeyPool pool, String header, String scheme) {
        return new ApiKeyFilter(pool, header, scheme, ROTATE_ON, logger);
    }

    private static ApiKeyPool pool(String... keys) {
        return new ApiKeyPool(List.of(keys), Duration.ofMinutes(1));
    }

    private static ProxyRequest request() {
        return new ProxyRequest("GET", "/v1/models", null,
                Map.of("Authorization", List.of("whatever-the-client-sent")), "10.0.0.9");
    }

    private static ProxyResponse response(int status) {
        return new ProxyResponse(status, Map.of(), new byte[0]);
    }

    @Test
    void theKeyIsWrittenOntoTheRequestUnderTheConfiguredHeader() {
        ProxyRequest outbound = filter(pool("first", "second"), "Authorization", "Bearer")
                .before(request(), new Attempt(2));

        assertEquals("Bearer first", outbound.header("Authorization"));
    }

    /** Replaced, not added to: two Authorization headers leave the choice to the upstream. */
    @Test
    void whateverTheClientSentUnderThatHeaderIsReplaced() {
        ProxyRequest outbound = filter(pool("first"), "Authorization", "Bearer")
                .before(request(), new Attempt(1));

        assertEquals(List.of("Bearer first"), outbound.headers().get("Authorization"));
    }

    /** The other common shape: a bare key under its own header, with no scheme word in front. */
    @Test
    void anEmptySchemeSendsTheBareKey() {
        ProxyRequest outbound = filter(pool("first"), "X-API-Key", "")
                .before(request(), new Attempt(1));

        assertEquals("first", outbound.header("X-API-Key"));
    }

    @Test
    void aRefusedKeyIsRestedAndTheRequestGoesRoundAgain() {
        ApiKeyPool pool = pool("first", "second");
        ApiKeyFilter filter = filter(pool, "Authorization", "Bearer");
        Attempt attempt = new Attempt(2);
        filter.before(request(), attempt);

        Retry retry = filter.after(request(), response(429), attempt);

        assertTrue(retry.retry());
        assertTrue(retry.reason().contains("429"), retry::reason);
        // Rested, so the next request skips it — the point of reacting at all.
        assertEquals(List.of("key #1"), pool.healthDetails().get("resting"));
    }

    /** A revoked or suspended key is as spent as a rate-limited one, and says so differently. */
    @Test
    void anAuthenticationFailureRotatesTheSameWayARateLimitDoes() {
        ApiKeyPool pool = pool("first", "second");
        ApiKeyFilter filter = filter(pool, "Authorization", "Bearer");
        Attempt attempt = new Attempt(2);
        filter.before(request(), attempt);

        assertTrue(filter.after(request(), response(401), attempt).retry());
    }

    @Test
    void anOrdinaryAnswerIsKeptAndCostsTheKeyNothing() {
        ApiKeyPool pool = pool("first", "second");
        ApiKeyFilter filter = filter(pool, "Authorization", "Bearer");
        Attempt attempt = new Attempt(2);
        filter.before(request(), attempt);

        assertFalse(filter.after(request(), response(200), attempt).retry());
        assertEquals(List.of(), pool.healthDetails().get("resting"));
        // A 500 is the upstream's problem, not the key's: rotating on it would rest every key in
        // the pool over one broken deployment.
        assertFalse(filter.after(request(), response(500), attempt).retry());
    }

    /**
     * One key is not a pool. Retrying would send the same credential to the same upstream and get
     * the same answer, one round trip later.
     */
    @Test
    void aSingleKeyIsRestedButNotRetried() {
        ApiKeyPool pool = pool("only");
        ApiKeyFilter filter = filter(pool, "Authorization", "Bearer");
        Attempt attempt = new Attempt(1);
        filter.before(request(), attempt);

        assertFalse(filter.after(request(), response(429), attempt).retry());
    }

    /**
     * The rule the whole class is built around. A proxy holding every key at once is exactly the
     * process that must never print one, so the position is what reaches the log, the fingerprint
     * and {@code /health}.
     */
    @Test
    void nothingTheFilterWritesAnywhereContainsAKey() {
        ApiKeyPool pool = pool("sk-live-super-secret", "sk-live-other-secret");
        ApiKeyFilter filter = filter(pool, "Authorization", "Bearer");
        Attempt attempt = new Attempt(2);
        filter.before(request(), attempt);

        filter.after(request(), response(429), attempt);

        assertFalse(logged.isEmpty(), "the refusal should be reported");
        assertFalse(logged.toString().contains("sk-live"), () -> "key leaked into the log: " + logged);
        assertFalse(filter.signature().contains("sk-live"),
                () -> "key leaked into the fingerprint: " + filter.signature());
        assertFalse(filter.healthDetails().toString().contains("sk-live"),
                () -> "key leaked into health: " + filter.healthDetails());
        assertTrue(logged.toString().contains("key #1"), () -> "the position should be named: " + logged);
    }

    @Test
    void configuredStatusesAreParsedAndNonsenseIsRefused() {
        assertEquals(Set.of(429, 503), ApiKeyFilter.statuses("429, 503"));
        // Empty is legal: spread requests, react to nothing.
        assertEquals(Set.of(), ApiKeyFilter.statuses(""));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyFilter.statuses("42"));
        assertThrows(NumberFormatException.class, () -> ApiKeyFilter.statuses("many"));
    }

    /** Changing what the filter does has to change what the audit says was in force. */
    @Test
    void reconfiguringTheFilterChangesItsFingerprint() {
        ApiKeyPool pool = pool("a", "b");

        assertFalse(filter(pool, "Authorization", "Bearer").signature()
                .equals(filter(pool, "X-API-Key", "").signature()));
    }
}

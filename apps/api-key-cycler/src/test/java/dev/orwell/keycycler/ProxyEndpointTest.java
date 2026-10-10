package dev.orwell.keycycler;

import dev.orwell.logging.LogEntry;
import dev.orwell.logging.LogLevel;
import dev.orwell.logging.Logger;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rotation loop end to end, against a real upstream on a loopback port: what a second forward
 * of the same buffered body actually does is a {@code java.net.http} question, and a mocked client
 * would answer it however the mock was written.
 */
class ProxyEndpointTest {
    private final List<String> upstreamCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<LogEntry> logged = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = logged::add;

    private JsonStubServer upstream;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = JsonStubServer.start();
    }

    @AfterEach
    void stopUpstream() {
        upstream.close();
    }

    /** Answers {@code status} for the first {@code failures} calls, then 200. */
    private void upstreamRefusing(int failures, int status) throws IOException {
        AtomicInteger seen = new AtomicInteger();
        upstream.on("/", exchange -> {
            upstreamCalls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization"));
            int answer = seen.getAndIncrement() < failures ? status : 200;
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
    }

    private ProxyEndpoint endpoint(int maxAttempts, String... keys) {
        ApiKeyPool pool = new ApiKeyPool(List.of(keys), Duration.ofMinutes(1));
        ApiKeyFilter filter = new ApiKeyFilter(pool, "Authorization", "Bearer",
                Set.of(429, 401, 403), logger);
        return new ProxyEndpoint(filter, new UpstreamClient(upstream.baseUrl(), Duration.ofSeconds(5)),
                logger, maxAttempts);
    }

    private static ProxyRequest request() {
        return new ProxyRequest("POST", "/work", null,
                Map.of("Authorization", List.of("client-supplied")), "10.0.0.9");
    }

    private LogEntry onlyProxiedEntry() {
        List<LogEntry> proxied = logged.stream()
                .filter(entry -> "Proxied request.".equals(entry.message()))
                .toList();
        assertEquals(1, proxied.size(), () -> "expected one proxied line, got " + logged);
        return proxied.getFirst();
    }

    @Test
    void theKeyReplacesWhateverTheClientSentAndTheUpstreamSeesIt() throws IOException {
        upstreamRefusing(0, 429);

        ProxyResponse response = endpoint(1, "first").handle(
                request(), "payload".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.status());
        // Replaced, not appended: the client's own value must not survive alongside the injected
        // one, or which of the two the upstream honours is its choice rather than ours.
        assertEquals(List.of("POST /work auth=Bearer first"), upstreamCalls);
    }

    @Test
    void aRefusedKeyIsFollowedByTheNextOne() throws IOException {
        upstreamRefusing(2, 429);

        ProxyResponse response = endpoint(3, "k1", "k2", "k3").handle(request(), new byte[0]);

        assertEquals(200, response.status());
        assertEquals(
                List.of("POST /work auth=Bearer k1",
                        "POST /work auth=Bearer k2",
                        "POST /work auth=Bearer k3"),
                upstreamCalls);
    }

    /**
     * One log line per client request however many trips it took, and the count on it. Without that
     * number {@code upstreamMs} — the sum across attempts — would describe a three-trip request and
     * a slow one-trip request identically.
     */
    @Test
    void oneRecordCountsTheAttemptsRatherThanOnePerTry() throws IOException {
        upstreamRefusing(2, 429);

        endpoint(3, "k1", "k2", "k3").handle(request(), new byte[0]);

        LogEntry entry = onlyProxiedEntry();
        assertEquals(LogLevel.INFO, entry.level());
        assertEquals(200, entry.metadata().get("status"));
        assertEquals(3, entry.metadata().get("attempts"));
        assertTrue((Long) entry.metadata().get("upstreamMs") <= (Long) entry.metadata().get("durationMs"),
                () -> "three upstream calls still cannot outlast the request: " + entry.metadata());
    }

    /** The overwhelming majority of requests. The key stays off the line rather than saying 1. */
    @Test
    void aSingleAttemptIsNotAnnouncedInTheLog() throws IOException {
        upstreamRefusing(0, 429);

        endpoint(2, "k1", "k2").handle(request(), new byte[0]);

        assertFalse(onlyProxiedEntry().metadata().containsKey("attempts"),
                () -> "unexpected attempts key: " + onlyProxiedEntry().metadata());
    }

    /**
     * The budget is the endpoint's. When every key has been refused the client gets the upstream's
     * own answer — with its Retry-After and its own explanation — rather than something we invented.
     */
    @Test
    void anExhaustedPoolRelaysTheUpstreamsRefusalRatherThanInventingOne() throws IOException {
        upstreamRefusing(Integer.MAX_VALUE, 429);

        ProxyResponse response = endpoint(2, "k1", "k2").handle(request(), new byte[0]);

        assertEquals(429, response.status());
        assertEquals(2, upstreamCalls.size(), () -> "budget was 2: " + upstreamCalls);
        assertTrue(logged.stream().anyMatch(entry -> entry.level() == LogLevel.WARN
                        && entry.message().startsWith("Every attempt was refused")),
                () -> "no warning that the pool was exhausted: " + logged);
    }

    /** A status the pool does not react to is the upstream's problem, and costs no key. */
    @Test
    void anUpstreamErrorIsNotTreatedAsASpentKey() throws IOException {
        upstreamRefusing(Integer.MAX_VALUE, 500);

        ProxyResponse response = endpoint(3, "k1", "k2", "k3").handle(request(), new byte[0]);

        assertEquals(500, response.status());
        assertEquals(1, upstreamCalls.size(), () -> "a 500 must not rotate: " + upstreamCalls);
    }

    @Test
    void anUnreachableUpstreamBecomes502() {
        ApiKeyPool pool = new ApiKeyPool(List.of("k1"), Duration.ofMinutes(1));
        ApiKeyFilter filter = new ApiKeyFilter(pool, "Authorization", "Bearer",
                Set.of(429), logger);
        // Port 1 on loopback: nothing listens, so the connection is refused immediately.
        ProxyEndpoint endpoint = new ProxyEndpoint(
                filter, new UpstreamClient("http://127.0.0.1:1", Duration.ofSeconds(5)), logger, 1);

        ProxyResponse response = endpoint.handle(request(), new byte[0]);

        assertEquals(502, response.status());
        assertTrue(response.bodyAsString().contains("upstream request failed"));
        assertEquals(LogLevel.ERROR, logged.getLast().level());
    }

    @Test
    void aRejectedRequestIsLoggedLikeAnyOther() throws IOException {
        upstreamRefusing(0, 429);

        ProxyResponse response = endpoint(1, "k1").reject(request(), 413, "request body too large");

        assertEquals(413, response.status());
        assertTrue(response.bodyAsString().contains("request body too large"));
        assertEquals(LogLevel.WARN, logged.getLast().level());
        assertEquals("request body too large", logged.getLast().metadata().get("reason"));
    }

    /**
     * The hop the correlation id exists to span. Without it the upstream mints an id of its own and
     * nothing joins this proxy's line to whatever the origin logged — which for a request that took
     * three keys is exactly when someone is trying to follow it.
     */
    @Test
    void theRequestIdIsForwardedAndSurvivesEveryRotation() throws IOException {
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger seen = new AtomicInteger();
        upstream.on("/", exchange -> {
            ids.add(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            exchange.sendResponseHeaders(seen.getAndIncrement() < 1 ? 429 : 200, -1);
            exchange.close();
        });
        ProxyRequest correlated = new ProxyRequest("GET", "/work", null,
                Map.of("X-Request-Id", List.of("caller-supplied-id")), "10.0.0.9");

        endpoint(2, "k1", "k2").handle(correlated, new byte[0]);

        assertEquals(List.of("caller-supplied-id", "caller-supplied-id"), ids);
        assertEquals("caller-supplied-id", onlyProxiedEntry().metadata().get("requestId"));
    }

    /** The rule the whole app is built around, asserted where a key could actually escape. */
    @Test
    void noKeyEverReachesTheLog() throws IOException {
        upstreamRefusing(1, 429);

        endpoint(2, "sk-live-first", "sk-live-second").handle(request(), new byte[0]);

        assertFalse(logged.toString().contains("sk-live"), () -> "key leaked into the log: " + logged);
        assertTrue(logged.toString().contains("key #1"), () -> "the position should be named: " + logged);
    }
}

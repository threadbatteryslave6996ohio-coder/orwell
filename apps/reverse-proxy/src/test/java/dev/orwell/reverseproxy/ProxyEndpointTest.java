package dev.orwell.reverseproxy;

import dev.orwell.http.RequestId;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the endpoint against a real upstream on a loopback port: the forwarding path is
 * mostly about what {@code java.net.http} accepts, which a mocked client would not tell us.
 */
class ProxyEndpointTest {
    private final List<String> upstreamCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<LogEntry> logged = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = logged::add;

    private JsonStubServer upstream;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = JsonStubServer.start();
        upstream.on("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            upstreamCalls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            byte[] response = "upstream ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Upstream", "yes");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    @AfterEach
    void stopUpstream() {
        upstream.close();
    }

    private ProxyEndpoint endpoint(Policy... policies) {
        return endpoint(upstream.baseUrl(), policies);
    }

    private ProxyEndpoint endpoint(String upstreamUrl, Policy... policies) {
        return new ProxyEndpoint(
                new PolicyChain(List.of(policies)),
                new UpstreamClient(upstreamUrl, Duration.ofSeconds(5)),
                logger);
    }

    private static ProxyRequest request(String method, String path, String query) {
        return new ProxyRequest(method, path, query, Map.of("X-Client", List.of("test")), "10.0.0.9");
    }

    private LogEntry onlyEntry() {
        assertEquals(1, logged.size(), () -> "expected exactly one log record, got " + logged);
        return logged.getFirst();
    }

    @Test
    void anAllowedRequestIsForwardedVerbatimAndLoggedAtInfo() {
        ProxyResponse response = endpoint().handle(request("POST", "/orders", "n=1"), "payload".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("POST /orders?n=1 payload"), upstreamCalls);
        assertEquals(201, response.status());
        assertEquals("upstream ok", response.bodyAsString());
        assertEquals(List.of("yes"), response.headers().get("X-Upstream"));

        LogEntry entry = onlyEntry();
        assertEquals(LogLevel.INFO, entry.level());
        assertEquals("Proxied request.", entry.message());
        assertEquals("POST", entry.metadata().get("method"));
        assertEquals("/orders?n=1", entry.metadata().get("target"));
        assertEquals(201, entry.metadata().get("status"));
        assertEquals("10.0.0.9", entry.metadata().get("remoteAddress"));
    }

    @Test
    void aBlockedRequestNeverReachesTheUpstream() {
        ProxyEndpoint endpoint = endpoint(PatternPolicy.fromConfiguration("/admin.*"));

        ProxyResponse response = endpoint.handle(request("GET", "/admin/users", null), new byte[0]);

        assertEquals(List.of(), upstreamCalls);
        assertEquals(403, response.status());
        assertTrue(response.bodyAsString().contains(ProxyEndpoint.DENIED_MESSAGE),
                () -> "unexpected body: " + response.bodyAsString());

        LogEntry entry = onlyEntry();
        assertEquals(LogLevel.WARN, entry.level());
        assertEquals("Request blocked by policy.", entry.message());
        assertEquals("pattern-policy", entry.metadata().get("policy"));
    }

    @Test
    void aPolicyThatThrowsBlocksTheRequestRatherThanFailingOpen() {
        ProxyEndpoint endpoint = endpoint(request -> {
            throw new IllegalStateException("policy is broken");
        });

        ProxyResponse response = endpoint.handle(request("GET", "/reports", null), new byte[0]);

        assertEquals(List.of(), upstreamCalls);
        assertEquals(403, response.status());
        assertEquals(List.of(LogLevel.ERROR, LogLevel.WARN), logged.stream().map(LogEntry::level).toList());
        assertEquals("policy-error", logged.getLast().metadata().get("policy"));
    }

    @Test
    void anUnreachableUpstreamBecomes502() {
        // Port 1 on loopback: nothing listens, so the connection is refused immediately.
        ProxyResponse response = endpoint("http://127.0.0.1:1").handle(request("GET", "/x", null), new byte[0]);

        assertEquals(502, response.status());
        assertTrue(response.bodyAsString().contains("upstream request failed"));
        assertEquals(LogLevel.ERROR, onlyEntry().level());
    }

    @Test
    void aRejectedRequestIsLoggedLikeAnyOther() {
        ProxyResponse response = endpoint().reject(request("PUT", "/upload", null), 413, "request body too large");

        assertEquals(413, response.status());
        assertTrue(response.bodyAsString().contains("request body too large"));
        assertEquals(LogLevel.WARN, onlyEntry().level());
        assertEquals(413, onlyEntry().metadata().get("status"));
    }

    @Test
    void theSendingServiceIsNamedInTheLogWhenTheHeaderIsPresent() {
        ProxyRequest attributed = new ProxyRequest(
                "GET", "/orders", null,
                // Lowercased on purpose: header lookup is case-insensitive, and java.net.http
                // sends what the caller wrote.
                Map.of("service", List.of("log-analyzer")), "10.0.0.9");

        endpoint().handle(attributed, new byte[0]);

        assertEquals("log-analyzer", onlyEntry().metadata().get("service"));
    }

    @Test
    void anUnattributedRequestLeavesTheKeyOutRatherThanLoggingNull() {
        endpoint().handle(request("GET", "/orders", null), new byte[0]);

        assertFalse(onlyEntry().metadata().containsKey("service"),
                () -> "unexpected service key: " + onlyEntry().metadata());
    }

    @Test
    void aBlockedRequestIsAttributedToo() {
        ProxyRequest attributed = new ProxyRequest(
                "GET", "/admin/users", null, Map.of("Service", List.of("insta")), "10.0.0.9");

        endpoint(PatternPolicy.fromConfiguration("/admin.*")).handle(attributed, new byte[0]);

        assertEquals(LogLevel.WARN, onlyEntry().level());
        assertEquals("insta", onlyEntry().metadata().get("service"));
    }

    @Test
    void healthDetailsReportTheUpstreamAndTheActivePolicies() {
        Map<String, Object> details = endpoint(PatternPolicy.fromConfiguration("/admin.*")).healthDetails();

        assertEquals(upstream.baseUrl(), details.get("upstream"));
        assertEquals(List.of("pattern-policy"), details.get("policies"));
        assertEquals(Map.of("enabled", false), details.get("audit"));
    }

    @Test
    void everyOutcomeReachesTheAuditStoreExactlyOnce() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
        ProxyEndpoint endpoint = new ProxyEndpoint(
                new PolicyChain(List.of(PatternPolicy.fromConfiguration("/admin.*"))),
                new UpstreamClient(upstream.baseUrl(), Duration.ofSeconds(5)),
                logger,
                collectingStore(stored));

        endpoint.handle(request("POST", "/orders", "n=1"), "payload".getBytes(StandardCharsets.UTF_8));
        endpoint.handle(request("GET", "/admin/users", null), new byte[0]);
        endpoint.reject(request("PUT", "/upload", null), 413, "request body too large");

        assertEquals(
                List.of(ProxyAuditRecord.Outcome.PROXIED,
                        ProxyAuditRecord.Outcome.BLOCKED,
                        ProxyAuditRecord.Outcome.REJECTED),
                stored.stream().map(ProxyAuditRecord::outcome).toList());
        // One record per request, and the same one the log line was built from.
        assertEquals(logged.size(), stored.size());
        assertEquals("/orders", stored.getFirst().path());
        assertEquals("n=1", stored.getFirst().query());
        assertEquals("pattern-policy", stored.get(1).policy());
        assertEquals("request body too large", stored.getLast().detail());
    }

    /**
     * The upstream and the ruleset are properties of the proxy, not of the request, so the endpoint
     * puts them on every record rather than each caller having to know them. Two proxies sharing
     * one audit table have nothing else to tell their rows apart.
     */
    @Test
    void everyStoredRecordCarriesTheProxysOwnUpstreamAndPolicies() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
        ProxyEndpoint endpoint = storingEndpoint(stored, PatternPolicy.fromConfiguration("/admin.*"));

        endpoint.handle(request("GET", "/orders", null), new byte[0]);
        endpoint.handle(request("GET", "/admin/users", null), new byte[0]);
        endpoint.reject(request("PUT", "/upload", null), 413, "too large");

        String upstreamUrl = upstream.baseUrl();
        assertEquals(List.of(upstreamUrl, upstreamUrl, upstreamUrl),
                stored.stream().map(ProxyAuditRecord::upstream).toList());
        String fingerprint = stored.getFirst().policies();
        assertTrue(fingerprint.startsWith("pattern-policy:"), () -> "unexpected ruleset: " + fingerprint);
        assertEquals(List.of(fingerprint, fingerprint, fingerprint),
                stored.stream().map(ProxyAuditRecord::policies).toList());
    }

    /**
     * The reason a row records a fingerprint rather than a list of names: editing the blocklist has
     * to be visible in the trail, or "was this allowed under today's rules or yesterday's?" cannot
     * be asked of the table — and every row before and after the edit would read alike.
     */
    @Test
    void reconfiguringAPolicyChangesWhatTheRowSaysWasInForce() {
        List<ProxyAuditRecord> before = Collections.synchronizedList(new ArrayList<>());
        List<ProxyAuditRecord> after = Collections.synchronizedList(new ArrayList<>());

        storingEndpoint(before, PatternPolicy.fromConfiguration("/admin.*"))
                .handle(request("GET", "/orders", null), new byte[0]);
        storingEndpoint(after, PatternPolicy.fromConfiguration("/admin.*,POST:/internal/.*"))
                .handle(request("GET", "/orders", null), new byte[0]);

        assertNotEquals(before.getFirst().policies(), after.getFirst().policies());
    }

    /** A pass-through is an empty list, not an unknown one. */
    @Test
    void aProxyWithNoPoliciesRecordsAnEmptyRulesetRatherThanNothing() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());

        storingEndpoint(stored).handle(request("GET", "/orders", null), new byte[0]);

        assertEquals("", stored.getFirst().policies());
    }

    /**
     * Two clocks, because one cannot answer the question that gets asked: {@code durationMs} is
     * what the client waited and {@code upstreamMs} is what the origin took, so the difference is
     * the proxy's own cost.
     */
    @Test
    void aProxiedRecordTimesTheUpstreamSeparatelyFromTheWholeRequest() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());

        storingEndpoint(stored).handle(request("GET", "/orders", null), new byte[0]);

        ProxyAuditRecord record = stored.getFirst();
        assertNotNull(record.upstreamMs(), "a forwarded request must time its upstream call");
        assertNotNull(record.durationMs());
        assertTrue(record.upstreamMs() <= record.durationMs(),
                () -> "upstream " + record.upstreamMs() + "ms cannot exceed the whole request "
                        + record.durationMs() + "ms");
    }

    /**
     * The useful half of a failure's timing: a connection refused in a millisecond and a request
     * that sat until the timeout are the same 502 to the client and very different problems.
     */
    @Test
    void anUpstreamFailureStillTimesTheAttempt() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
        ProxyEndpoint endpoint = new ProxyEndpoint(
                new PolicyChain(List.of()),
                new UpstreamClient("http://127.0.0.1:1", Duration.ofSeconds(5)),
                logger,
                collectingStore(stored));

        endpoint.handle(request("GET", "/x", null), new byte[0]);

        assertNotNull(stored.getFirst().upstreamMs(), "a failed forward still spent time trying");
    }

    /**
     * The id the filter already bound to this thread wins over a fresh one. Minting again would
     * answer the client one id — the filter puts it on the response — and store another, so the id
     * a caller reads off its own response would match no row in {@code request_audit}.
     */
    @Test
    void aRequestWithNoInboundIdAdoptsTheOneTheFilterAlreadyBound() {
        RequestId.set("filter-bound-id");
        try {
            assertEquals("filter-bound-id", request("GET", "/orders", null).requestId());
        } finally {
            RequestId.clear();
        }
    }

    /** Outside a request there is no thread id to adopt, so one is minted rather than left null. */
    @Test
    void aRequestOutsideAnyFilterStillGetsAnId() {
        RequestId.clear();

        assertNotNull(request("GET", "/orders", null).requestId());
    }

    /**
     * The hop the id exists to span. Without this the upstream mints a third id and nothing joins
     * the proxy's row to whatever the origin logged.
     */
    @Test
    void theRequestIdIsForwardedToTheUpstream() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        upstream.on("/correlated", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst(RequestId.HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        ProxyRequest correlated = new ProxyRequest(
                "GET", "/correlated", null, Map.of(RequestId.HEADER, List.of("caller-supplied-id")), "10.0.0.9");

        endpoint().handle(correlated, new byte[0]);

        assertEquals("caller-supplied-id", seen.get());
    }

    /** And the id the proxy resolved for a caller that sent none — the case that was silently lost. */
    @Test
    void anUnattributedRequestForwardsTheIdTheProxyResolvedForIt() throws IOException {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> seen = new AtomicReference<>();
        upstream.on("/uncorrelated", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst(RequestId.HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        storingEndpoint(stored).handle(request("GET", "/uncorrelated", null), new byte[0]);

        assertNotNull(seen.get(), "the upstream would otherwise mint an id of its own");
        assertEquals(stored.getFirst().requestId(), seen.get());
    }

    /**
     * The filter has already put the id on the response, and an orwell upstream echoes back the one
     * it was sent — so relaying the upstream's copy would tell the client its id twice.
     */
    @Test
    void theUpstreamsEchoedRequestIdIsNotRelayedBack() throws IOException {
        upstream.on("/echoing", exchange -> {
            exchange.getResponseHeaders().add(RequestId.HEADER, "caller-supplied-id");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        ProxyRequest correlated = new ProxyRequest(
                "GET", "/echoing", null, Map.of(RequestId.HEADER, List.of("caller-supplied-id")), "10.0.0.9");

        ProxyResponse response = endpoint().handle(correlated, new byte[0]);

        assertFalse(response.headers().containsKey(RequestId.HEADER),
                () -> "the filter owns this header on the response: " + response.headers());
    }

    /** Every record has an id, so a log line and its audit row can always be joined. */
    @Test
    void theRequestIdReachesBothTheLogAndTheRecord() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
        ProxyRequest correlated = new ProxyRequest(
                "GET", "/orders", null, Map.of("X-Request-Id", List.of("caller-supplied-id")), "10.0.0.9");

        storingEndpoint(stored).handle(correlated, new byte[0]);

        assertEquals("caller-supplied-id", stored.getFirst().requestId());
        assertEquals("caller-supplied-id", onlyEntry().metadata().get("requestId"));
    }

    @Test
    void anUpstreamFailureIsStoredAsFailedWithItsError() {
        List<ProxyAuditRecord> stored = Collections.synchronizedList(new ArrayList<>());
        ProxyEndpoint endpoint = new ProxyEndpoint(
                new PolicyChain(List.of()),
                new UpstreamClient("http://127.0.0.1:1", Duration.ofSeconds(5)),
                logger,
                collectingStore(stored));

        endpoint.handle(request("GET", "/x", null), new byte[0]);

        ProxyAuditRecord record = stored.getFirst();
        assertEquals(ProxyAuditRecord.Outcome.FAILED, record.outcome());
        assertEquals(502, record.status());
        assertTrue(record.detail().contains("Exception"), () -> "unexpected detail: " + record.detail());
    }

    private ProxyEndpoint storingEndpoint(List<ProxyAuditRecord> stored, Policy... policies) {
        return new ProxyEndpoint(
                new PolicyChain(List.of(policies)),
                new UpstreamClient(upstream.baseUrl(), Duration.ofSeconds(5)),
                logger,
                collectingStore(stored));
    }

    private static AuditStore collectingStore(List<ProxyAuditRecord> stored) {
        return new AuditStore() {
            @Override
            public void submit(ProxyAuditRecord record) {
                stored.add(record);
            }

            @Override
            public Map<String, Object> healthDetails() {
                return Map.of("enabled", true);
            }

            @Override
            public void close() {
            }
        };
    }
}

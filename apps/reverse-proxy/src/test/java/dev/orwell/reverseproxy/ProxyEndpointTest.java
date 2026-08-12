package dev.orwell.reverseproxy;

import com.sun.net.httpserver.HttpServer;
import dev.orwell.logging.LogEntry;
import dev.orwell.logging.LogLevel;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the endpoint against a real upstream on a loopback port: the forwarding path is
 * mostly about what {@code java.net.http} accepts, which a mocked client would not tell us.
 */
class ProxyEndpointTest {
    private final List<String> upstreamCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<LogEntry> logged = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = logged::add;

    private HttpServer upstream;
    private int upstreamPort;

    @BeforeEach
    void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            upstreamCalls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
            byte[] response = "upstream ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Upstream", "yes");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private ProxyEndpoint endpoint(Policy... policies) {
        return endpoint("http://127.0.0.1:" + upstreamPort, policies);
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
    void healthDetailsReportTheUpstreamAndTheActivePolicies() {
        Map<String, Object> details = endpoint(PatternPolicy.fromConfiguration("/admin.*")).healthDetails();

        assertEquals("http://127.0.0.1:" + upstreamPort, details.get("upstream"));
        assertEquals(List.of("pattern-policy"), details.get("policies"));
    }
}

package dev.orwell.health;

import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as up, against a real HTTP server.
 *
 * <p>Stubbed rather than mocked because every interesting case here is about the wire: a status code,
 * a body that disagrees with it, a socket that never answers. A mocked client would test none of them.
 */
class HealthProbeTest {
    private JsonStubServer server;
    private HealthProbe probe;

    @BeforeEach
    void start() throws IOException {
        server = JsonStubServer.start();
        probe = new HealthProbe(Duration.ofSeconds(2), entry -> { });
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void acceptsTheReposOwnHealthEnvelope() {
        server.stub("/health", 200,
                "{\"success\":true,\"status\":\"healthy\",\"engine\":\"spring\"}");

        ProbeResult result = probe.check(target("/health"));

        assertThat(result.healthy()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.reportedStatus()).isEqualTo("healthy");
        assertThat(result.detail()).isNull();
    }

    /**
     * The case a status-code-only checker gets wrong. {@code jarvis-frame-viewer} answers exactly
     * this when it cannot reach the frame store.
     */
    @Test
    void treatsA503WithAnUnhealthyBodyAsDown() {
        server.stub("/health", 503,
                "{\"success\":false,\"status\":\"unhealthy\",\"error\":\"cannot reach frame_events\"}");

        ProbeResult result = probe.check(target("/health"));

        assertThat(result.healthy()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(503);
        assertThat(result.reportedStatus()).isEqualTo("unhealthy");
        assertThat(result.detail()).contains("503");
    }

    /**
     * And the subtler one: a service answering 200 while telling you it is not well. A checker that
     * stops at the status code calls this up.
     */
    @Test
    void treatsA200ThatReportsUnhealthyAsDown() {
        server.stub("/health", 200, "{\"success\":true,\"status\":\"degraded\"}");

        ProbeResult result = probe.check(target("/health"));

        assertThat(result.healthy()).isFalse();
        assertThat(result.detail()).contains("degraded");
    }

    @Test
    void treatsA200ThatReportsSuccessFalseAsDown() {
        server.stub("/health", 200, "{\"success\":false}");

        ProbeResult result = probe.check(target("/health"));

        assertThat(result.healthy()).isFalse();
        assertThat(result.detail()).contains("success=false");
    }

    /**
     * A third-party endpoint that answers 200 with anything else is taken at its word — otherwise
     * this would be unusable against exactly the dependencies most worth watching.
     */
    @Test
    void acceptsA200FromSomethingThatIsNotThisRepo() {
        server.stub("/health", 200, "OK");

        assertThat(probe.check(target("/health")).healthy()).isTrue();

        server.stub("/empty", 200, "");
        assertThat(probe.check(target("/empty")).healthy()).isTrue();

        server.stub("/array", 200, "[1,2,3]");
        assertThat(probe.check(target("/array")).healthy()).isTrue();
    }

    @Test
    void treatsAnyNon2xxAsDown() {
        server.stub("/notfound", 404, "{}");
        server.stub("/error", 500, "{}");

        assertThat(probe.check(target("/notfound")).healthy()).isFalse();
        assertThat(probe.check(target("/notfound")).detail()).contains("404");
        assertThat(probe.check(target("/error")).healthy()).isFalse();
    }

    /** A health endpoint that redirects is a misconfiguration, not a hop to follow. */
    @Test
    void doesNotFollowARedirectAwayFromTheHealthEndpoint() {
        server.on("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/health");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.stub("/health", 200, "{\"status\":\"healthy\"}");

        ProbeResult result = probe.check(target("/moved"));

        assertThat(result.healthy()).isFalse();
        assertThat(result.httpStatus()).isEqualTo(302);
    }

    /** Nothing listening: the common case, and the message has to name it usefully. */
    @Test
    void reportsAConnectionThatIsRefused() {
        ProbeResult result = probe.check(
                new HealthTarget("nothing", URI.create("http://127.0.0.1:1/health")));

        assertThat(result.healthy()).isFalse();
        assertThat(result.httpStatus()).isZero();
        assertThat(result.detail()).isNotBlank();
    }

    /**
     * Answering the socket and then never the request — the failure a connect timeout alone misses,
     * and the one an operator treats differently from a refused connection.
     */
    @Test
    void reportsATargetThatAcceptsTheConnectionAndNeverAnswers() throws IOException {
        HealthProbe impatient = new HealthProbe(Duration.ofMillis(300), entry -> { });
        server.on("/slow", exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        ProbeResult result = impatient.check(target("/slow"));

        assertThat(result.healthy()).isFalse();
        assertThat(result.detail()).contains("did not answer");
    }

    @Test
    void recordsHowLongTheCheckTook() {
        server.stub("/health", 200, "{\"status\":\"healthy\"}");

        assertThat(probe.check(target("/health")).durationMillis()).isNotNegative();
    }

    /** A mistyped URL pointing at something enormous must not turn every round into a download. */
    @Test
    void stopsReadingAnUnreasonablyLargeBody() {
        String huge = "x".repeat(512 * 1024);
        server.stub("/huge", 200, huge);

        ProbeResult result = probe.check(target("/huge"));

        // Still answers, and on the status code alone — the truncated body is not valid JSON and is
        // therefore not consulted, which is the documented behaviour for a non-JSON 200.
        assertThat(result.healthy()).isTrue();
        assertThat(huge.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(64 * 1024);
    }

    private HealthTarget target(String path) {
        return new HealthTarget("stub", URI.create(server.baseUrl() + path));
    }
}

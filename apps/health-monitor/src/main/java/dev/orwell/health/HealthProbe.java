package dev.orwell.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.logging.Logger;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Asks one endpoint how it is, and decides whether the answer counts as up.
 *
 * <p><strong>A 200 is necessary and not sufficient.</strong> The repo's health endpoints answer with
 * {@code {"success":true,"status":"healthy",...}}, and some of them mean it conditionally:
 * {@code jarvis-frame-viewer} answers 503 with {@code "status":"unhealthy"} when it cannot reach the
 * frame store, and any app is free to do the same. A checker that reads only the status code would
 * call a service healthy the moment it starts lying politely — so the body is read, and a
 * {@code status} that is not {@code healthy} or a {@code success} that is false counts as down
 * whatever the code was.
 *
 * <p>An endpoint that answers 200 with something that is not JSON, or with JSON carrying neither
 * field, is taken at its word and counts as up. That is deliberate: this has to be usable against
 * things outside this repo, and demanding the repo's envelope from a third-party endpoint would make
 * it useless for exactly the dependencies most worth watching.
 *
 * <p><strong>Redirects are not followed</strong> ({@code HttpClient} defaults to
 * {@code Redirect.NEVER}), so a 302 reads as down. A health endpoint that redirects is a
 * misconfiguration — usually a reverse proxy in front of a service that is not there — and following
 * it would report the health of whatever answered instead.
 */
public final class HealthProbe {
    /**
     * How much of the response body is read before giving up on it.
     *
     * <p>A health endpoint answers in a few hundred bytes. This exists for the mistyped URL that
     * points at something enormous: without it, one wrong setting turns every round into a download.
     */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration timeout;

    /**
     * @param logger kept for symmetry with every other component's constructor, and unused. See the
     *               note on the client below before adding a call to it.
     */
    public HealthProbe(Duration timeout, Logger logger) {
        Objects.requireNonNull(logger, "logger");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        // NOT wrapped in LoggingHttpClient, which is a documented exception to the repo's rule rather
        // than an oversight — the same shape as object-storage-proxy's AnalysisWorker: a record per
        // request where one already exists, at the wrong granularity.
        //
        // This client sends one request per target per interval forever. Fourteen targets on a
        // thirty-second cycle is ~40,000 records a day, every one of them saying a service that is
        // fine is still fine, and each failed probe would add a WARN *per round* for as long as an
        // outage lasted. The information is already recorded, better: the outcome of every probe is on
        // /targets, and the only part worth a line — the transition — is written by
        // HealthMonitorService at ERROR. Wrapping here would bury that line in its own telemetry.
        //
        // The alert client in HealthMonitorService *is* wrapped: a handful of requests a year, where a
        // record per request is exactly the right granularity.
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** Checks one target. Never throws: every failure is a result saying why. */
    public ProbeResult check(HealthTarget target) {
        long startedAt = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(target.url())
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String body = read(response.body());
            long took = millisSince(startedAt);

            String reported = reportedStatus(body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ProbeResult.down(response.statusCode(), reported, took,
                        "answered HTTP " + response.statusCode());
            }
            if (reported != null && !"healthy".equalsIgnoreCase(reported)) {
                // 200 and unhappy: the endpoint is reachable and telling us something is wrong.
                return ProbeResult.down(response.statusCode(), reported, took,
                        "reported status '" + reported + "'");
            }
            if (saysUnsuccessful(body)) {
                return ProbeResult.down(response.statusCode(), reported, took,
                        "reported success=false");
            }
            return ProbeResult.up(response.statusCode(), reported, took);
        } catch (HttpTimeoutException exception) {
            // Named separately from the general IO case because it is the one an operator acts on
            // differently: the service is answering the socket and not the request.
            return ProbeResult.down(0, null, millisSince(startedAt),
                    "did not answer within " + timeout.toSeconds() + "s");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProbeResult.down(0, null, millisSince(startedAt), "the check was interrupted");
        } catch (Exception exception) {
            // Connection refused, unknown host, TLS failure. The message is what an operator needs;
            // the class name alone ("ConnectException") says nothing about which host.
            return ProbeResult.down(0, null, millisSince(startedAt), reason(exception));
        }
    }

    /** The {@code status} field, or null when the body is not JSON or does not carry one. */
    private String reportedStatus(String body) {
        JsonNode node = parsed(body);
        return node != null && node.hasNonNull("status") ? node.get("status").asText() : null;
    }

    private boolean saysUnsuccessful(String body) {
        JsonNode node = parsed(body);
        return node != null && node.hasNonNull("success") && node.get("success").isBoolean()
                && !node.get("success").asBoolean();
    }

    private JsonNode parsed(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (Exception exception) {
            // Not JSON. Fine — see the class comment; the status code stands on its own.
            return null;
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            // The response arrived but the body did not. The status code is still the answer.
            return "";
        }
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static long millisSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}

package dev.orwell.insta.apify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.insta.InstaJson;
import dev.orwell.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs Apify actors, two ways.
 *
 * <p>{@link #runActor} uses {@code run-sync-get-dataset-items}: start the run, wait for it, get the
 * dataset back, one HTTP call. It is the cheapest possible path and the profile lookup — one item,
 * a few seconds — has no reason to want anything else. Its limit is Apify's: the whole run must
 * finish inside 300 seconds.
 *
 * <p>{@link #runActorWithOutput} starts the run asynchronously and long-polls it, then reads the
 * dataset and the {@code OUTPUT} record separately. Three or four HTTP calls instead of one, in
 * exchange for the two things the sync endpoint cannot give: no 300-second ceiling, and sight of
 * {@code OUTPUT}, where the connections actor puts its continuation cursor. If we give up waiting,
 * the run is aborted rather than left running — an orphaned run keeps billing.
 *
 * <p>The token goes in the {@code Authorization} header rather than the {@code ?token=} query
 * parameter Apify's docs favour, so it cannot leak through a URL into a proxy or access log.
 */
public class ApifyClient {
    /** Apify rejects a sync run asking for longer than this. The async path has no such ceiling. */
    static final int MAX_RUN_TIMEOUT_SECONDS = 300;
    /** Apify's maximum server-side wait per poll. */
    private static final int MAX_WAIT_FOR_FINISH_SECONDS = 60;
    /**
     * Statuses worth stopping the poll on. {@code TIMING-OUT} and {@code ABORTING} are not
     * terminal, but a run in either is already doomed, so waiting out its death throes only spends
     * the caller's budget.
     */
    private static final Set<String> SETTLED_STATUSES =
            Set.of("SUCCEEDED", "FAILED", "ABORTED", "TIMED-OUT", "TIMING-OUT", "ABORTING");
    private static final int LOGGED_BODY_LIMIT = 500;
    private static final long POLL_FLOOR_MILLIS = 250;

    private final ObjectMapper json = InstaJson.mapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String token;
    private final int runTimeoutSeconds;
    private final Logger logger;

    public ApifyClient(
            String baseUrl, String token, int runTimeoutSeconds, Logger logger) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.token = Objects.requireNonNull(token, "token");
        this.runTimeoutSeconds = Math.max(runTimeoutSeconds, 1);
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Runs {@code actorId} (in either {@code user/name} or {@code user~name} form) with
     * {@code input} as its JSON input and returns the run's dataset items, at most
     * {@code maxItems} of them.
     *
     * <p>{@code maxItems} is sent to Apify as well as applied here: on pay-per-result actors it
     * bounds what the run is allowed to charge for, which a client-side truncation would not.
     *
     * @throws ApifyException if the run failed, timed out, or returned something that is not a
     *                        dataset array.
     */
    public List<JsonNode> runActor(String actorId, Map<String, Object> input, int maxItems) {
        int timeout = Math.min(runTimeoutSeconds, MAX_RUN_TIMEOUT_SECONDS);
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(
                        baseUrl + "/v2/acts/" + actorPath(actorId) + "/run-sync-get-dataset-items"
                                + "?timeout=" + timeout + "&maxItems=" + maxItems + "&format=json"))
                // One second of slack over the actor's own budget, so the run's timeout wins the
                // race and we get Apify's error rather than a socket read one.
                .timeout(Duration.ofSeconds(timeout + 1L))
                .POST(HttpRequest.BodyPublishers.ofString(serialize(input))), actorId);

        if (!isSuccess(response.statusCode())) {
            throw failure(actorId, response.statusCode(), response.body());
        }
        return items(actorId, response.body(), maxItems);
    }

    /**
     * Runs {@code actorId} asynchronously, waits for it, and returns both its dataset items and its
     * {@code OUTPUT} record. Use this when the run's {@code OUTPUT} matters — otherwise
     * {@link #runActor} costs three fewer round trips.
     *
     * @throws ApifyException if the run failed, was aborted, or outlived
     *                        {@code APIFY_RUN_TIMEOUT_SECONDS}.
     */
    public ActorRun runActorWithOutput(String actorId, Map<String, Object> input, int maxItems) {
        long deadline = System.nanoTime() + Duration.ofSeconds(runTimeoutSeconds).toNanos();

        HttpResponse<String> started = send(HttpRequest.newBuilder(URI.create(
                        baseUrl + "/v2/acts/" + actorPath(actorId) + "/runs"
                                + "?timeout=" + runTimeoutSeconds + "&maxItems=" + maxItems
                                + "&waitForFinish=" + waitFor(deadline)))
                .timeout(Duration.ofSeconds(MAX_WAIT_FOR_FINISH_SECONDS + 10L))
                .POST(HttpRequest.BodyPublishers.ofString(serialize(input))), actorId);

        if (!isSuccess(started.statusCode())) {
            throw failure(actorId, started.statusCode(), started.body());
        }
        JsonNode run = parse(actorId, started.body()).path("data");
        String runId = run.path("id").asText(null);
        if (runId == null) {
            throw new ApifyException("Apify did not report a run id.", ApifyException.Kind.UNAVAILABLE);
        }

        run = awaitTerminal(actorId, run, runId, deadline);
        String status = run.path("status").asText("");
        if (!"SUCCEEDED".equals(status)) {
            throw runEndedBadly(actorId, runId, status);
        }
        return new ActorRun(
                datasetItems(actorId, run.path("defaultDatasetId").asText(null), maxItems),
                outputRecord(actorId, run.path("defaultKeyValueStoreId").asText(null)));
    }

    /** Long-polls the run until it reaches a terminal status or we run out of time. */
    private JsonNode awaitTerminal(String actorId, JsonNode started, String runId, long deadline) {
        JsonNode run = started;
        while (!SETTLED_STATUSES.contains(run.path("status").asText(""))) {
            int wait = waitFor(deadline);
            if (wait <= 0) {
                // Nobody is waiting for this run any more, and a running actor keeps charging.
                abort(actorId, runId);
                throw new ApifyException(
                        "The Apify actor run did not finish within " + runTimeoutSeconds + "s.",
                        ApifyException.Kind.TIMED_OUT);
            }
            // waitForFinish normally parks this call server-side, but a status Apify considers
            // final-enough to answer immediately would otherwise spin us at full speed.
            pause();
            HttpResponse<String> polled = send(HttpRequest.newBuilder(
                    URI.create(baseUrl + "/v2/actor-runs/" + runId + "?waitForFinish=" + wait))
                    .timeout(Duration.ofSeconds(wait + 10L)).GET(), actorId);
            if (!isSuccess(polled.statusCode())) {
                throw failure(actorId, polled.statusCode(), polled.body());
            }
            run = parse(actorId, polled.body()).path("data");
        }
        return run;
    }

    private ApifyException runEndedBadly(String actorId, String runId, String status) {
        logger.error("Apify actor run ended without succeeding.", Map.of(
                "actor", actorId, "runId", runId, "status", status));
        if (status.startsWith("TIM")) {
            return new ApifyException("The Apify actor run timed out.", ApifyException.Kind.TIMED_OUT);
        }
        return new ApifyException("The Apify actor run ended as " + status + ".",
                ApifyException.Kind.UNAVAILABLE);
    }

    private List<JsonNode> datasetItems(String actorId, String datasetId, int maxItems) {
        if (datasetId == null) {
            throw new ApifyException("Apify did not report a dataset for the run.",
                    ApifyException.Kind.UNAVAILABLE);
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(
                        baseUrl + "/v2/datasets/" + datasetId + "/items"
                                + "?limit=" + maxItems + "&format=json"))
                .timeout(Duration.ofSeconds(60)).GET(), actorId);
        if (!isSuccess(response.statusCode())) {
            throw failure(actorId, response.statusCode(), response.body());
        }
        return items(actorId, response.body(), maxItems);
    }

    /** @return the {@code OUTPUT} record, or {@code null} — plenty of actors never write one. */
    private JsonNode outputRecord(String actorId, String keyValueStoreId) {
        if (keyValueStoreId == null) {
            return null;
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(
                        baseUrl + "/v2/key-value-stores/" + keyValueStoreId + "/records/OUTPUT"))
                .timeout(Duration.ofSeconds(30)).GET(), actorId);
        if (response.statusCode() == 404) {
            return null;
        }
        if (!isSuccess(response.statusCode())) {
            // A missing OUTPUT costs us the cursor, not the accounts — never fail the run over it.
            logger.warn("Could not read the Apify run OUTPUT record.", Map.of(
                    "actor", actorId, "statusCode", response.statusCode()));
            return null;
        }
        try {
            return json.readTree(response.body());
        } catch (Exception exception) {
            logger.warn("The Apify run OUTPUT record was not JSON.", Map.of("actor", actorId));
            return null;
        }
    }

    /** Best-effort: if the abort fails the run stays alive, which is no worse than not trying. */
    private void abort(String actorId, String runId) {
        try {
            http.send(authorized(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/v2/actor-runs/" + runId + "/abort")))
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            logger.warn("Aborted an Apify run that outlived its budget.", Map.of(
                    "actor", actorId, "runId", runId));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.warn("Could not abort an Apify run that outlived its budget.", Map.of(
                    "actor", actorId, "runId", runId));
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String actorId) {
        try {
            return http.send(authorized(request).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Interrupted while waiting for the Apify actor run.",
                    ApifyException.Kind.UNAVAILABLE, exception);
        } catch (Exception exception) {
            // getMessage() is null for plenty of transport exceptions, so this map has to take nulls.
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("actor", actorId);
            metadata.put("error", exception.toString());
            logger.error("Could not reach Apify.", metadata);
            throw new ApifyException("Could not reach Apify.", ApifyException.Kind.UNAVAILABLE, exception);
        }
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder request) {
        return request.header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token);
    }

    /**
     * Apify does not document a status code for an exhausted balance, so the error <em>type</em> in
     * the body decides — it is stable across whatever status accompanies it, and telling "out of
     * credit" apart from "actor is broken" is the difference between topping up and debugging.
     */
    private ApifyException failure(String actorId, int status, String body) {
        String type = errorType(body);
        logger.error("Apify actor run failed.", Map.of(
                "actor", actorId, "statusCode", status, "errorType", type, "body", truncate(body)));

        if (status == 408 || type.contains("timeout")) {
            return new ApifyException(
                    "The Apify actor run did not finish within " + runTimeoutSeconds + "s.",
                    ApifyException.Kind.TIMED_OUT);
        }
        // Throttling first: "rate-limit-exceeded" would otherwise be caught by any looser
        // "limit exceeded" test below and reported as an empty wallet.
        if (status == 429 || type.contains("rate-limit")) {
            return new ApifyException("Apify is rate limiting this account.",
                    ApifyException.Kind.RATE_LIMITED);
        }
        if (status == 402 || type.contains("usage-hard-limit") || type.contains("usage-limit")
                || type.contains("insufficient")) {
            return new ApifyException("The Apify account is out of usage credit.",
                    ApifyException.Kind.OUT_OF_CREDIT);
        }
        if (status == 401 || status == 403) {
            return new ApifyException("Apify rejected the API token.",
                    ApifyException.Kind.TOKEN_REJECTED);
        }
        return new ApifyException("Apify returned HTTP " + status + " for actor " + actorId + ".",
                ApifyException.Kind.UNAVAILABLE);
    }

    private String errorType(String body) {
        try {
            JsonNode type = json.readTree(body).path("error").path("type");
            return type.isTextual() ? type.asText().toLowerCase(Locale.ROOT) : "";
        } catch (Exception exception) {
            return "";
        }
    }

    private List<JsonNode> items(String actorId, String body, int maxItems) {
        JsonNode parsed = parse(actorId, body);
        if (!parsed.isArray()) {
            logger.error("Apify returned dataset items that are not an array.", Map.of(
                    "actor", actorId, "body", truncate(body)));
            throw new ApifyException("Apify returned dataset items in an unexpected shape.",
                    ApifyException.Kind.UNAVAILABLE);
        }
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : parsed) {
            if (items.size() == maxItems) {
                break;
            }
            items.add(item);
        }
        return items;
    }

    private JsonNode parse(String actorId, String body) {
        try {
            return json.readTree(body);
        } catch (Exception exception) {
            logger.error("Apify returned a body that is not JSON.", Map.of(
                    "actor", actorId, "body", truncate(body)));
            throw new ApifyException("Apify returned a response that is not JSON.",
                    ApifyException.Kind.UNAVAILABLE, exception);
        }
    }

    private String serialize(Map<String, Object> input) {
        try {
            return json.writeValueAsString(input);
        } catch (Exception exception) {
            throw new ApifyException("Could not serialize the Apify actor input.",
                    ApifyException.Kind.UNAVAILABLE, exception);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_FLOOR_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApifyException("Interrupted while waiting for the Apify actor run.",
                    ApifyException.Kind.UNAVAILABLE, exception);
        }
    }

    /** Seconds left before {@code deadline}, capped at Apify's per-poll maximum. */
    private static int waitFor(long deadline) {
        long remaining = Duration.ofNanos(deadline - System.nanoTime()).toSeconds();
        return (int) Math.min(Math.max(remaining, 0), MAX_WAIT_FOR_FINISH_SECONDS);
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static String actorPath(String actorId) {
        return actorId.replace('/', '~');
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOGGED_BODY_LIMIT ? body : body.substring(0, LOGGED_BODY_LIMIT) + "…";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

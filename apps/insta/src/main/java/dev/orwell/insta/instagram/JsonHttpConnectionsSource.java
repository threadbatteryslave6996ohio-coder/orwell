package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.insta.InstaJson;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link ConnectionsSource} that is a plain JSON HTTP endpoint rather than an Apify actor.
 *
 * <p>Everything the two of those have in common lives here: sending the request, turning a status
 * code into an {@link ApifyException.Kind} the chain can fail over on, and finding the accounts
 * array and continuation token in a response whose exact shape nobody controls. A subclass supplies
 * only the URI and the headers.
 *
 * <p>The array-finding is deliberately lenient, in the same spirit as {@link DatasetFields} and for
 * the same reason: these endpoints are interchangeable and disagree about wrapping far more often
 * than about meaning. One returns a bare array, another {@code {"data":{"items":[…]}}}, another
 * {@code {"users":[…]}}. Guessing among a fixed list of shapes is what lets a different endpoint be
 * configured without a code change — and when none of them match, that is an
 * {@link ApifyException.Kind#UNAVAILABLE}, never an empty list. <b>An empty list is the one answer
 * this must never invent</b>: to {@code FollowWriter} it is indistinguishable from every follower
 * leaving at once.
 */
abstract class JsonHttpConnectionsSource implements ConnectionsSource {
    /** Where the accounts array hides, in rough order of how often it is seen. */
    private static final String[][] ITEM_PATHS = {
        {"accounts"}, {"items"}, {"users"}, {"data"},
        {"data", "items"}, {"data", "users"}, {"data", "accounts"},
        {"response", "items"}, {"result", "items"},
    };
    /** Continuation token spellings. {@code end_cursor} and {@code next_max_id} are Instagram's. */
    private static final String[] TOKEN_NAMES = {
        "nextCursor", "next_cursor", "cursor", "pagination_token", "paginationToken",
        "end_cursor", "endCursor", "next_max_id", "nextMaxId", "after",
    };
    private static final int LOGGED_BODY_LIMIT = 300;

    private final HttpClient http;
    private final String name;
    protected final Logger logger;

    protected JsonHttpConnectionsSource(String name, Logger logger, Duration connectTimeout) {
        this.name = Objects.requireNonNull(name, "name");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.http = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build(), logger);
    }

    @Override
    public final String name() {
        return name;
    }

    /** The URL for one page. */
    protected abstract URI uri(String username, ConnectionType type, int limit, String cursor);

    /** Headers to send, authentication included. */
    protected abstract Map<String, String> headers();

    /** How long to wait for the whole response. */
    protected abstract Duration requestTimeout();

    @Override
    public Result fetch(String username, ConnectionType type, int limit, String cursor) {
        URI uri = uri(username, type, limit, cursor);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout())
                .header("Accept", "application/json")
                .GET();
        headers().forEach(request::header);

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw failure("could not be reached", uri, ApifyException.Kind.UNAVAILABLE, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("was interrupted", uri, ApifyException.Kind.UNAVAILABLE, exception);
        }

        if (response.statusCode() / 100 != 2) {
            throw status(response, uri);
        }
        JsonNode body;
        try {
            body = InstaJson.mapper().readTree(response.body());
        } catch (Exception exception) {
            throw failure("returned a body that is not JSON", uri,
                    ApifyException.Kind.UNAVAILABLE, exception);
        }
        return new Result(items(body, uri), supportsCursor() ? token(body) : null);
    }

    /**
     * Turns a status into a kind. The three the chain fails over on are separated from the ones it
     * must not: a rejected key is worth trying the next source for, a malformed request is not —
     * every source would reject it identically.
     */
    private ApifyException status(HttpResponse<String> response, URI uri) {
        int code = response.statusCode();
        ApifyException.Kind kind = switch (code) {
            case 401, 403 -> ApifyException.Kind.UNAVAILABLE;
            case 402 -> ApifyException.Kind.OUT_OF_CREDIT;
            case 408, 504 -> ApifyException.Kind.TIMED_OUT;
            case 429 -> ApifyException.Kind.RATE_LIMITED;
            // 404 says "not here", which is a statement about this source, not about the handle:
            // a mistyped INSTA_RAPIDAPI_FOLLOWERS_PATH answers 404 while another vendor serves the
            // same walk perfectly. It was falling through to TOKEN_REJECTED — which is documented
            // as "the token was refused", is outside the failover set, and so killed the whole
            // chain. If the profile really is gone every source answers 404 and the walk still
            // ends; that costs one request per remaining source, against losing the chain outright.
            case 404 -> ApifyException.Kind.UNAVAILABLE;
            default -> code >= 500
                    ? ApifyException.Kind.UNAVAILABLE
                    : ApifyException.Kind.TOKEN_REJECTED;
        };
        // 4xx that is not one of the above is a request this source will never accept, and
        // TOKEN_REJECTED is outside the failover set — so it stops the walk rather than spending
        // the next source's quota on the identical mistake.
        logger.warn("A connections source refused a request.", Map.of(
                "source", name, "status", code, "uri", uri.getHost() + uri.getPath(),
                "kind", kind.name(), "body", truncate(response.body())));
        return new ApifyException(
                name + " answered HTTP " + code + " for " + uri.getHost() + uri.getPath(), kind);
    }

    /**
     * The accounts array, wherever it is. Absent is a failure, not an empty page — see the class
     * comment for why that distinction is the important one here.
     */
    private List<JsonNode> items(JsonNode body, URI uri) {
        if (body != null && body.isArray()) {
            return toList(body);
        }
        for (String[] path : ITEM_PATHS) {
            JsonNode node = body;
            for (String segment : path) {
                node = node == null ? null : node.get(segment);
            }
            if (node != null && node.isArray()) {
                return toList(node);
            }
        }
        throw failure("returned no recognisable accounts array", uri,
                ApifyException.Kind.UNAVAILABLE, null);
    }

    /** Null when the source says there is no more, which is what ends a walk. */
    private String token(JsonNode body) {
        for (JsonNode scope : new JsonNode[] {body, body.get("paging"), body.get("pagination"),
                body.get("data"), body.get("meta")}) {
            if (scope == null || !scope.isObject()) {
                continue;
            }
            String value = DatasetFields.text(scope, TOKEN_NAMES);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> items = new ArrayList<>(array.size());
        array.forEach(items::add);
        return items;
    }

    private ApifyException failure(
            String what, URI uri, ApifyException.Kind kind, Throwable cause) {
        String message = name + " " + what + " (" + uri.getHost() + uri.getPath() + ")";
        logger.error("A connections source failed.", Map.of(
                "source", name, "uri", uri.getHost() + uri.getPath(), "kind", kind.name(),
                "error", cause == null ? what : cause.toString()));
        return cause == null
                ? new ApifyException(message, kind)
                : new ApifyException(message, kind, cause);
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOGGED_BODY_LIMIT ? body : body.substring(0, LOGGED_BODY_LIMIT);
    }
}

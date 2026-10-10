package dev.orwell.insta.scraper;

import dev.orwell.logging.Logger;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code GET /connections} — the one route insta's {@code self-hosted} source calls.
 *
 * <pre>
 * GET /connections?username=nasa&amp;type=followers&amp;limit=500[&amp;cursor=…]
 * 200 {"accounts":[…], "nextCursor": "…"}      // null = the list is exhausted
 * </pre>
 *
 * <p>It collects Instagram pages until it has {@code limit} accounts, runs out of list, or spends
 * its wall-clock budget — Instagram's own page is around 50, and doing that loop here rather than
 * making insta do it saves a round trip per 50 accounts without changing what either side means.
 *
 * <p><b>The cursor is the whole contract.</b> Returned non-null it means "more remain"; null means
 * "that was all of them", and insta takes null as licence to retire every follower absent from the
 * walk. So stopping early for <em>any</em> reason — limit reached, time budget spent — must return
 * the cursor we stopped at. The only null is Instagram's own {@code next_max_id} being absent.
 *
 * <p>Errors never degrade into an empty list. A refused session is a 401, a spent budget a 429,
 * anything unrecognised a 502 — each of which insta maps to a failover onto a paid actor, where an
 * empty 200 would be recorded as a mass unfollow.
 */
public final class ConnectionsEndpoint implements HttpHandler {
    private static final HttpString RETRY_AFTER = new HttpString("Retry-After");

    private final InstagramWebClient instagram;
    private final String token;
    private final Duration timeBudget;
    private final Logger logger;

    public ConnectionsEndpoint(
            InstagramWebClient instagram, String token, Duration timeBudget, Logger logger) {
        this.instagram = Objects.requireNonNull(instagram, "instagram");
        this.token = token == null ? "" : token.trim();
        this.timeBudget = Objects.requireNonNull(timeBudget, "timeBudget");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        // Undertow's IO threads must not block, and every request here waits on a rate limiter and
        // several network calls.
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }
        exchange.startBlocking();

        if (!authorized(exchange)) {
            UndertowHttp.sendError(exchange, 401, "Bad or missing bearer token.");
            return;
        }

        String username = query(exchange, "username");
        InstagramWebClient.Direction direction =
                InstagramWebClient.Direction.parse(query(exchange, "type"));
        if (username == null || username.isBlank()) {
            UndertowHttp.sendError(exchange, 400, "username is required.");
            return;
        }
        if (direction == null) {
            UndertowHttp.sendError(exchange, 400, "type must be followers or following.");
            return;
        }
        int limit = positive(query(exchange, "limit"), 200);
        String cursor = query(exchange, "cursor");

        try {
            UndertowHttp.sendJson(exchange, 200, collect(username, direction, limit, cursor));
        } catch (InstagramWebClient.ScrapeException failure) {
            logger.warn("A connections request failed.", Map.of(
                    "username", username, "type", direction.name(),
                    "status", failure.status(), "error", failure.getMessage()));
            if (failure.retryAfterSeconds() > 0) {
                exchange.getResponseHeaders().put(
                        RETRY_AFTER, String.valueOf(failure.retryAfterSeconds()));
            }
            UndertowHttp.sendError(exchange, failure.status(), failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            UndertowHttp.sendError(exchange, 503, "The scraper is shutting down.");
        }
    }

    private Map<String, Object> collect(
            String username, InstagramWebClient.Direction direction, int limit, String cursor)
            throws InstagramWebClient.ScrapeException, InterruptedException {

        // Resolving the handle is a request of its own, so it counts against the budget like any
        // other. A resumed walk still pays it: the cursor is opaque to us and carries no id.
        String userId = instagram.userId(username);

        List<InstagramWebClient.Account> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeBudget);
        String next = cursor;
        boolean exhausted = false;

        while (collected.size() < limit) {
            String requested = next;
            InstagramWebClient.Page page = instagram.connections(userId, direction, requested);
            collected.addAll(page.accounts());
            next = page.nextMaxId();
            if (next == null) {
                // Instagram itself said there is no more. The only path to a null cursor.
                exhausted = true;
                break;
            }
            if (next.equals(requested)) {
                // A cursor that does not advance would re-fetch the same page until the hourly
                // RequestBudget was gone, then 429 — having spent every slot on one page. insta's
                // own walkAll stops on exactly this condition; so does this. Not `exhausted`: we
                // do not know the list ended, and saying so would let a caller retire the rest.
                logger.warn("Stopped a walk whose cursor stopped advancing.", Map.of(
                        "username", username, "collected", collected.size()));
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                // Out of time, not out of list. Returning `next` is what keeps that distinction.
                logger.info("Returned a partial page against the time budget.", Map.of(
                        "username", username, "collected", collected.size()));
                break;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", collected);
        body.put("nextCursor", exhausted ? null : next);
        logger.info("Served a connections page.", Map.of(
                "username", username, "type", direction.name(),
                "accounts", collected.size(), "exhausted", exhausted));
        return body;
    }

    private boolean authorized(HttpServerExchange exchange) {
        if (token.isBlank()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.equals("Bearer " + token);
    }

    private static String query(HttpServerExchange exchange, String name) {
        var values = exchange.getQueryParameters().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static int positive(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim().toLowerCase(Locale.ROOT));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}

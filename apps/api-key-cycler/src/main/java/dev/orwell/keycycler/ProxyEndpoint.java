package dev.orwell.keycycler;

import dev.orwell.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-independent proxy behavior: let {@link ApiKeyFilter} put a key on the request, forward it,
 * let the filter look at the answer, and go round again with the next key when it asks. Every
 * request produces exactly one {@link ProxyRecord} however many upstream trips it took.
 *
 * <p>There is no policy chain here and nothing is stored. This proxy admits everything and exists
 * to change the request, not to judge or record it — {@code apps/reverse-proxy} is the one with
 * policies and an audit table, and pointing {@code CYCLER_UPSTREAM_URL} at it puts every rotated
 * request in that trail without either app knowing about the other.
 */
public final class ProxyEndpoint {
    /** What a client is told when the filter could not put a key on the request. */
    public static final String UNPREPARED_MESSAGE = "the proxy could not prepare this request";

    private final ApiKeyFilter keys;
    private final UpstreamClient upstream;
    private final Logger logger;
    private final int maxAttempts;

    public ProxyEndpoint(ApiKeyFilter keys, UpstreamClient upstream, Logger logger, int maxAttempts) {
        this.keys = Objects.requireNonNull(keys, "keys");
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive: " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    public ProxyResponse handle(ProxyRequest request, byte[] body) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(body, "body");
        long startedAt = System.nanoTime();

        Attempt attempt = new Attempt(maxAttempts);
        // Summed across attempts rather than measured on the last one: what the operator wants to
        // know is what this request cost the origin in total, and `attempts` on the same record is
        // what makes that number readable.
        long upstreamNanos = 0;
        while (true) {
            ProxyRequest outbound;
            try {
                // From the client's request every time, not from the last attempt's: the header
                // rewritten for a key that has since been rate-limited must not be carried into
                // the retry that rate limit asked for.
                outbound = keys.before(request, attempt);
            } catch (RuntimeException exception) {
                // Fails closed. The filter is here to put a credential on the request, and
                // forwarding one without it only earns a 401 the client cannot act on.
                failed(request, 500, exception.toString(), startedAt, upstreamNanos, attempt.number(),
                        "Could not put an API key on the request.");
                return ProxyResponse.error(500, UNPREPARED_MESSAGE);
            }

            // Timed separately from the request as a whole: `durationMs` is what the client waited,
            // `upstreamMs` is what the origin took, and the gap between them is this proxy's own
            // cost — which for a rotating proxy is most of what anyone wants to know.
            long forwardedAt = System.nanoTime();
            try {
                ProxyResponse response = upstream.forward(outbound, body);
                upstreamNanos += System.nanoTime() - forwardedAt;

                if (retryWanted(outbound, response, attempt)) {
                    attempt.next();
                    continue;
                }
                ProxyRecord record = ProxyRecord.proxied(request, response.status(), body.length,
                        response.body().length, millisSince(startedAt), millis(upstreamNanos),
                        attempt.number());
                logger.info("Proxied request.", record.metadata());
                return response;
            } catch (IllegalArgumentException exception) {
                // Deliberately NOT exception.toString(). The only IllegalArgumentException this
                // call can throw comes from HttpRequest.Builder rejecting a header value, and its
                // message embeds that value — which for this proxy is the API key. ApiKeyPool now
                // refuses such a key at startup, so reaching here means the JDK found something it
                // did not; a fixed string keeps the credential out of the log either way. Nothing
                // this proxy says about itself may contain one.
                upstreamNanos += System.nanoTime() - forwardedAt;
                failed(request, 502, "upstream request could not be built", startedAt,
                        upstreamNanos, attempt.number(), "Upstream request could not be built.");
                return ProxyResponse.error(502, "upstream request failed");
            } catch (IOException exception) {
                upstreamNanos += System.nanoTime() - forwardedAt;
                failed(request, 502, exception.toString(), startedAt, upstreamNanos, attempt.number(),
                        "Upstream request failed.");
                return ProxyResponse.error(502, "upstream request failed");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                upstreamNanos += System.nanoTime() - forwardedAt;
                failed(request, 502, exception.toString(), startedAt, upstreamNanos, attempt.number(),
                        "Upstream request interrupted.");
                return ProxyResponse.error(502, "upstream request interrupted");
            }
        }
    }

    /**
     * Records and answers a request the transport itself refused (an oversized body), so those
     * requests appear in the log alongside the ones that reached a key.
     */
    public ProxyResponse reject(ProxyRequest request, int status, String reason) {
        ProxyRecord record = ProxyRecord.rejected(request, status, reason);
        logger.warn("Request rejected.", record.metadata());
        return ProxyResponse.error(status, reason);
    }

    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("upstream", upstream.upstreamUrl());
        details.put("maxAttempts", maxAttempts);
        details.put("keyPool", keys.healthDetails());
        return details;
    }

    /**
     * Whether to go round again, and the one place a retry is decided.
     *
     * <p>A filter that asks once the budget is spent gets a WARN rather than silence: the client is
     * about to be handed the very response the rotation existed to replace — a 429 from an
     * exhausted pool — and "the proxy tried every key" is a different operational problem from
     * "the proxy never rotated", which the 429 alone cannot distinguish.
     */
    private boolean retryWanted(ProxyRequest outbound, ProxyResponse response, Attempt attempt) {
        Retry retry;
        try {
            retry = keys.after(outbound, response, attempt);
        } catch (RuntimeException exception) {
            // Unlike a throwing `before`, this cannot fail the request: the upstream has already
            // answered and that answer is valid. Keep it, and say loudly that something is broken.
            logger.error("Deciding whether to rotate failed; keeping the response.", metadata(outbound,
                    "status", response.status(),
                    "attempt", attempt.number(),
                    "error", exception.toString()));
            return false;
        }
        if (!retry.retry()) {
            return false;
        }
        if (attempt.isLast()) {
            logger.warn("Every attempt was refused; answering with the last upstream response.",
                    metadata(outbound,
                            "status", response.status(),
                            "attempts", attempt.number(),
                            "reason", retry.reason()));
            return false;
        }
        logger.info("Retrying upstream request with the next key.", metadata(outbound,
                "status", response.status(),
                "attempt", attempt.number(),
                "reason", retry.reason()));
        return true;
    }

    /**
     * {@code upstreamMs} is recorded for a failure too, and it is the useful half: it separates a
     * connection refused in a millisecond from a request that sat until the timeout, which are the
     * same 502 to the client and very different problems.
     */
    private void failed(
            ProxyRequest request,
            int status,
            String error,
            long startedAt,
            long upstreamNanos,
            int attempts,
            String message) {
        ProxyRecord record = ProxyRecord.failed(request, status, error,
                millisSince(startedAt), millis(upstreamNanos), attempts);
        logger.error(message, record.metadata());
    }

    /**
     * The identifying half of a record's metadata, for the lines built by hand rather than from a
     * {@link ProxyRecord}. The request id is on every one of them deliberately: without it the
     * lines that say <em>why</em> a request was retried could not be joined to the one that says it
     * was served.
     */
    private static Map<String, Object> metadata(ProxyRequest request, Object... pairs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", request.requestId());
        metadata.put("method", request.method());
        metadata.put("target", request.target());
        metadata.put("remoteAddress", request.remoteAddress());
        for (int index = 0; index < pairs.length; index += 2) {
            metadata.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return metadata;
    }

    private static long millisSince(long startedAt) {
        return millis(System.nanoTime() - startedAt);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }
}

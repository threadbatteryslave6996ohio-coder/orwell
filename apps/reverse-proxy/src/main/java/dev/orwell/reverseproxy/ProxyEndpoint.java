package dev.orwell.reverseproxy;

import dev.orwell.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-independent proxy behavior: run the policy chain, log the request, forward what survives.
 * Every request produces exactly one log record — an allowed one at INFO, a refused one at WARN —
 * so the log is a complete audit of what the proxy was asked to do, not only of what it blocked.
 */
public final class ProxyEndpoint {
    /** What a client is told when a policy refuses the request. */
    public static final String DENIED_MESSAGE = "policies do not allow you to send this request";

    private final PolicyChain policies;
    private final UpstreamClient upstream;
    private final Logger logger;

    public ProxyEndpoint(PolicyChain policies, UpstreamClient upstream, Logger logger) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public ProxyResponse handle(ProxyRequest request, byte[] body) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(body, "body");
        long startedAt = System.nanoTime();

        PolicyDecision decision = evaluate(request);
        if (!decision.allowed()) {
            logger.warn("Request blocked by policy.", metadata(request, Map.of(
                    "policy", decision.policy(),
                    "status", 403)));
            return ProxyResponse.error(403, DENIED_MESSAGE);
        }

        try {
            ProxyResponse response = upstream.forward(request, body);
            logger.info("Proxied request.", metadata(request, Map.of(
                    "status", response.status(),
                    "requestBytes", body.length,
                    "responseBytes", response.body().length,
                    "durationMs", millisSince(startedAt))));
            return response;
        } catch (IOException | IllegalArgumentException exception) {
            logger.error("Upstream request failed.", metadata(request, Map.of(
                    "error", exception.toString(),
                    "upstream", upstream.upstreamUrl(),
                    "durationMs", millisSince(startedAt))));
            return ProxyResponse.error(502, "upstream request failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("Upstream request interrupted.", metadata(request, Map.of(
                    "upstream", upstream.upstreamUrl(),
                    "durationMs", millisSince(startedAt))));
            return ProxyResponse.error(502, "upstream request interrupted");
        }
    }

    /**
     * Records and answers a request the transport itself refused (an oversized body), so those
     * requests appear in the log alongside the ones that reached the policy chain.
     */
    public ProxyResponse reject(ProxyRequest request, int status, String reason) {
        logger.warn("Request rejected.", metadata(request, Map.of(
                "status", status,
                "reason", reason)));
        return ProxyResponse.error(status, reason);
    }

    public Map<String, Object> healthDetails() {
        return Map.of(
                "upstream", upstream.upstreamUrl(),
                "policies", policies.names());
    }

    private PolicyDecision evaluate(ProxyRequest request) {
        try {
            return policies.evaluate(request);
        } catch (RuntimeException exception) {
            // A policy that blows up must not fail open. Refuse the request, and say loudly why —
            // a silent deny would look identical to a working blocklist.
            logger.error("Policy evaluation failed; blocking the request.", metadata(request, Map.of(
                    "error", exception.toString())));
            return PolicyDecision.deny("policy-error");
        }
    }

    private static Map<String, Object> metadata(ProxyRequest request, Map<String, ?> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("method", request.method());
        metadata.put("target", request.target());
        metadata.put("remoteAddress", request.remoteAddress());
        metadata.putAll(extra);
        return metadata;
    }

    private static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

package dev.orwell.reverseproxy;

import dev.orwell.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-independent proxy behavior: run the policy chain, record the request, forward what
 * survives. Every request produces exactly one {@link ProxyAuditRecord} — an allowed one logged at
 * INFO, a refused one at WARN — and that record goes two places: the shared {@link Logger}, and
 * the {@link AuditStore}, which is what makes the trail queryable rather than greppable. Both come
 * from the same object on purpose, so the log and the table cannot describe a request differently.
 */
public final class ProxyEndpoint {
    /** What a client is told when a policy refuses the request. */
    public static final String DENIED_MESSAGE = "policies do not allow you to send this request";

    private final PolicyChain policies;
    private final UpstreamClient upstream;
    private final Logger logger;
    private final AuditStore audit;
    /**
     * Which upstream and which ruleset were in force, resolved once. Both are fixed for the life of
     * the process, so recomputing them per request would only be a chance for two records of the
     * same request to disagree.
     */
    private final ProxyAuditRecord.Context context;

    /** Logs but stores nothing — the shape a test wants, and what an unconfigured store gives. */
    public ProxyEndpoint(PolicyChain policies, UpstreamClient upstream, Logger logger) {
        this(policies, upstream, logger, AuditStore.DISABLED);
    }

    public ProxyEndpoint(PolicyChain policies, UpstreamClient upstream, Logger logger, AuditStore audit) {
        this.policies = Objects.requireNonNull(policies, "policies");
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.audit = Objects.requireNonNull(audit, "audit");
        // The fingerprint, not the names: a row has to say which *configuration* was in force, or
        // editing PROXY_BLOCKED_PATTERNS leaves every row before and after reading alike. An empty
        // chain stores an empty string, not null — "this proxy ran no policies" is a fact worth
        // being able to query, and it is not the same as "we do not know what ran".
        this.context = new ProxyAuditRecord.Context(upstream.upstreamUrl(), policies.fingerprint());
    }

    public ProxyResponse handle(ProxyRequest request, byte[] body) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(body, "body");
        Instant receivedAt = Instant.now();
        long startedAt = System.nanoTime();

        PolicyDecision decision = evaluate(request);
        if (!decision.allowed()) {
            ProxyAuditRecord record = ProxyAuditRecord.blocked(receivedAt, request, context, decision.policy());
            logger.warn("Request blocked by policy.", record.metadata());
            audit.submit(record);
            return ProxyResponse.error(403, DENIED_MESSAGE);
        }

        // Timed separately from the request as a whole: `durationMs` is what the client waited,
        // `upstreamMs` is what the origin took, and the gap between them is this proxy's own cost.
        // One number could not tell a slow upstream from a slow policy chain.
        long forwardedAt = System.nanoTime();
        try {
            ProxyResponse response = upstream.forward(request, body);
            ProxyAuditRecord record = ProxyAuditRecord.proxied(receivedAt, request, context,
                    response.status(), body.length, response.body().length,
                    millisSince(startedAt), millisSince(forwardedAt));
            logger.info("Proxied request.", record.metadata());
            audit.submit(record);
            return response;
        } catch (IOException | IllegalArgumentException exception) {
            failed(receivedAt, request, exception.toString(), startedAt, forwardedAt, "Upstream request failed.");
            return ProxyResponse.error(502, "upstream request failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failed(receivedAt, request, exception.toString(), startedAt, forwardedAt,
                    "Upstream request interrupted.");
            return ProxyResponse.error(502, "upstream request interrupted");
        }
    }

    /**
     * Records and answers a request the transport itself refused (an oversized body), so those
     * requests appear in the trail alongside the ones that reached the policy chain.
     */
    public ProxyResponse reject(ProxyRequest request, int status, String reason) {
        ProxyAuditRecord record = ProxyAuditRecord.rejected(Instant.now(), request, context, status, reason);
        logger.warn("Request rejected.", record.metadata());
        audit.submit(record);
        return ProxyResponse.error(status, reason);
    }

    public Map<String, Object> healthDetails() {
        return Map.of(
                "upstream", upstream.upstreamUrl(),
                "policies", policies.names(),
                "audit", audit.healthDetails());
    }

    /**
     * {@code upstreamMs} is recorded for a failure too, and it is the useful half: it separates a
     * connection refused in a millisecond from a request that sat until the timeout, which are the
     * same 502 to the client and very different problems.
     *
     * <p>The upstream itself is not added to the metadata here. It is on the record — and so in the
     * table — as a column now, and it is the same value on every line this process writes; the
     * startup record names it once.
     */
    private void failed(
            Instant receivedAt, ProxyRequest request, String error, long startedAt, long forwardedAt, String message) {
        ProxyAuditRecord record = ProxyAuditRecord.failed(
                receivedAt, request, context, error, millisSince(startedAt), millisSince(forwardedAt));
        logger.error(message, record.metadata());
        audit.submit(record);
    }

    private PolicyDecision evaluate(ProxyRequest request) {
        try {
            return policies.evaluate(request);
        } catch (RuntimeException exception) {
            // A policy that blows up must not fail open. Refuse the request, and say loudly why —
            // a silent deny would look identical to a working blocklist. The refusal itself is
            // recorded by the caller, under the policy name `policy-error`.
            Map<String, Object> metadata = new LinkedHashMap<>();
            // Hand-built, so the id has to be put on deliberately — every other record on this path
            // gets it from ProxyAuditRecord.metadata(). Without it the one line that says *why* a
            // request was refused is the only one that cannot be joined to its audit row.
            metadata.put("requestId", request.requestId());
            metadata.put("method", request.method());
            metadata.put("target", request.target());
            metadata.put("remoteAddress", request.remoteAddress());
            metadata.put("error", exception.toString());
            logger.error("Policy evaluation failed; blocking the request.", metadata);
            return PolicyDecision.deny("policy-error");
        }
    }

    private static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}

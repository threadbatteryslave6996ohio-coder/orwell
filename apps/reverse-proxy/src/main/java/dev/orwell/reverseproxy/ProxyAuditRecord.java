package dev.orwell.reverseproxy;

import dev.orwell.http.ServiceHeaderHttpClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One request, as the proxy saw it. This is the audit trail's unit: it becomes both the log
 * record and the {@code request_audit} row, so the two cannot drift into describing the same
 * request differently.
 *
 * <p>Nullable fields are the ones that genuinely do not apply to every outcome — a blocked request
 * has no {@code durationMs} because nothing was forwarded, an allowed one has no {@code policy}.
 * They are left out of the log metadata and stored as SQL {@code NULL} rather than being filled
 * with zeroes, so {@code WHERE policy IS NOT NULL} means what it says.
 * *
 * <p>Four fields answer "who", and they answer different questions on purpose. {@code
 * remoteAddress} is the socket, which behind another hop is that hop; {@code forwardedFor} is what
 * that hop says the original address was; {@code service} is the calling <em>process</em>, from the
 * header {@link ServiceHeaderHttpClient} stamps; {@code clientId} is the authenticated
 * <em>identity</em>, which is the only one of the four that means a person or a credential rather
 * than a machine. A workspace would belong here too, but it is not on the wire — it is per-app
 * configuration on the caller's side — so it is left out rather than guessed at.
 */
public record ProxyAuditRecord(
        Instant receivedAt,
        Outcome outcome,
        String requestId,
        String service,
        String clientId,
        String method,
        String path,
        String query,
        String remoteAddress,
        String forwardedFor,
        String userAgent,
        String upstream,
        String policies,
        int status,
        String policy,
        Integer requestBytes,
        Integer responseBytes,
        Long durationMs,
        Long upstreamMs,
        String detail
) {
    /** The header a caller behind another proxy arrives with. */
    static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    /** The header carrying the authenticated identity, as the 401 guard reads it. */
    static final String CLIENT_ID_HEADER = "X-Client-Id";
    private static final String USER_AGENT_HEADER = "User-Agent";

    /** What the proxy did with the request. Stored verbatim in the {@code outcome} column. */
    public enum Outcome {
        /** Passed every policy and the upstream answered. */
        PROXIED,
        /** A policy refused it; the upstream never saw it. */
        BLOCKED,
        /** The transport refused it before the policy chain — an oversized body. */
        REJECTED,
        /** Allowed, but the upstream was unreachable, timed out, or the call was interrupted. */
        FAILED
    }

    /**
     * What the proxy knows about itself while it serves a request: which upstream it forwards to
     * and which ruleset was in force. Both are constant per process and neither is derivable from
     * the request, so they are handed in rather than looked up.
     */
    public record Context(String upstream, String policies) {
        public Context {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(policies, "policies");
        }
    }

    public ProxyAuditRecord {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
    }

    static ProxyAuditRecord proxied(
            Instant receivedAt,
            ProxyRequest request,
            Context context,
            int status,
            int requestBytes,
            int responseBytes,
            long durationMs,
            long upstreamMs) {
        return record(receivedAt, Outcome.PROXIED, request, context, status, null,
                requestBytes, responseBytes, durationMs, upstreamMs, null);
    }

    static ProxyAuditRecord blocked(Instant receivedAt, ProxyRequest request, Context context, String policy) {
        return record(receivedAt, Outcome.BLOCKED, request, context, 403, policy,
                null, null, null, null, null);
    }

    static ProxyAuditRecord rejected(
            Instant receivedAt, ProxyRequest request, Context context, int status, String reason) {
        return record(receivedAt, Outcome.REJECTED, request, context, status, null,
                null, null, null, null, reason);
    }

    static ProxyAuditRecord failed(
            Instant receivedAt, ProxyRequest request, Context context, String error,
            long durationMs, Long upstreamMs) {
        return record(receivedAt, Outcome.FAILED, request, context, 502, null,
                null, null, durationMs, upstreamMs, error);
    }

    private static ProxyAuditRecord record(
            Instant receivedAt,
            Outcome outcome,
            ProxyRequest request,
            Context context,
            int status,
            String policy,
            Integer requestBytes,
            Integer responseBytes,
            Long durationMs,
            Long upstreamMs,
            String detail) {
        return new ProxyAuditRecord(
                receivedAt,
                outcome,
                request.requestId(),
                header(request, ServiceHeaderHttpClient.SERVICE_HEADER),
                header(request, CLIENT_ID_HEADER),
                request.method(),
                request.path(),
                request.query(),
                request.remoteAddress(),
                header(request, FORWARDED_FOR_HEADER),
                header(request, USER_AGENT_HEADER),
                context.upstream(),
                context.policies(),
                status,
                policy,
                requestBytes,
                responseBytes,
                durationMs,
                upstreamMs,
                detail);
    }

    /** The request target as it appeared on the request line — the log's view, not the table's. */
    public String target() {
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    /**
     * The record as logger metadata. Keys match the columns, except that the table splits
     * {@code path} and {@code query} (so a query can be searched without a {@code LIKE} across the
     * whole target) while the log keeps the single {@code target} a reader expects to see.
     *
     * <p>{@code upstream} and {@code policies} are columns but not metadata: they are the same on
     * every line one process writes, so repeating them would cost log volume to say nothing. The
     * table is where cross-process queries happen, and there they are what tells two proxies apart.
     */
    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "requestId", requestId);
        metadata.put("method", method);
        metadata.put("target", target());
        metadata.put("remoteAddress", remoteAddress);
        put(metadata, "forwardedFor", forwardedFor);
        put(metadata, "service", service);
        put(metadata, "clientId", clientId);
        metadata.put("status", status);
        put(metadata, "policy", policy);
        put(metadata, "requestBytes", requestBytes);
        put(metadata, "responseBytes", responseBytes);
        put(metadata, "durationMs", durationMs);
        put(metadata, "upstreamMs", upstreamMs);
        // The same column under the name each outcome has always used in the log.
        put(metadata, outcome == Outcome.REJECTED ? "reason" : "error", detail);
        return metadata;
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String header(ProxyRequest request, String name) {
        String value = request.header(name);
        return value == null || value.isBlank() ? null : value;
    }
}

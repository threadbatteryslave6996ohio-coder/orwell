package dev.orwell.keycycler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One request, as the proxy saw it, in the shape the log wants. This proxy keeps no database —
 * rotating credentials is not an audit function — so unlike the reverse proxy's record this has
 * one destination and no columns to answer to.
 *
 * <p>Nullable fields are the ones that do not apply to every outcome: a request the filter could
 * not prepare has no sizes because nothing was forwarded. They are left out of the metadata rather
 * than logged as null, so a line carries what happened and nothing else.
 *
 * <p>{@code attempts} is how many times the request went upstream. It has to be here because it is
 * what keeps {@code upstreamMs} honest: that number is the sum across attempts, so without the
 * count a request that took three trips and one that took a slow single trip read alike.
 */
public record ProxyRecord(
        String requestId,
        String method,
        String target,
        String remoteAddress,
        int status,
        Integer requestBytes,
        Integer responseBytes,
        Long durationMs,
        Long upstreamMs,
        int attempts,
        String detail
) {
    public ProxyRecord {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
    }

    static ProxyRecord proxied(
            ProxyRequest request,
            int status,
            int requestBytes,
            int responseBytes,
            long durationMs,
            long upstreamMs,
            int attempts) {
        return new ProxyRecord(request.requestId(), request.method(), request.target(),
                request.remoteAddress(), status, requestBytes, responseBytes,
                durationMs, upstreamMs, attempts, null);
    }

    static ProxyRecord failed(
            ProxyRequest request,
            int status,
            String error,
            long durationMs,
            long upstreamMs,
            int attempts) {
        return new ProxyRecord(request.requestId(), request.method(), request.target(),
                request.remoteAddress(), status, null, null, durationMs, upstreamMs, attempts, error);
    }

    static ProxyRecord rejected(ProxyRequest request, int status, String reason) {
        return new ProxyRecord(request.requestId(), request.method(), request.target(),
                request.remoteAddress(), status, null, null, null, null, 0, reason);
    }

    /**
     * The record as logger metadata.
     *
     * <p>{@code attempts} appears only when a request cost more than one trip. Almost every request
     * is one attempt, and {@code attempts: 1} on every line would be log volume spent saying
     * nothing; its absence means one.
     */
    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", requestId);
        metadata.put("method", method);
        metadata.put("target", target);
        metadata.put("remoteAddress", remoteAddress);
        metadata.put("status", status);
        put(metadata, "requestBytes", requestBytes);
        put(metadata, "responseBytes", responseBytes);
        put(metadata, "durationMs", durationMs);
        put(metadata, "upstreamMs", upstreamMs);
        if (attempts > 1) {
            metadata.put("attempts", attempts);
        }
        // A body the transport refused is a `reason`; anything else that went wrong is an `error`.
        put(metadata, status == 413 ? "reason" : "error", detail);
        return metadata;
    }

    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}

package dev.orwell.keycycler;

import dev.orwell.http.RequestId;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Framework-neutral view of an inbound request: what {@link ApiKeyFilter} rewrites and what
 * {@link UpstreamClient} forwards.
 *
 * <p>{@code path} and {@code query} stay in their wire (percent-encoded) form. The proxy
 * reassembles them into the upstream URI verbatim, so decoding here would corrupt any request
 * whose path carries an encoded slash, space or {@code ?}.
 *
 * <p>The body is deliberately not part of this record. The filter decides on the request line and
 * headers, which the proxy has in full before it has read a byte of the body.
 */
public record ProxyRequest(
        String method,
        String path,
        String query,
        Map<String, List<String>> headers,
        String remoteAddress,
        String requestId
) {
    /**
     * Without an explicit id: the inbound {@code X-Request-Id} when it is usable, otherwise the one
     * this thread is already serving under, and only failing both a fresh one — so a
     * {@code ProxyRequest} always has an id, and a test or a caller that does not care about
     * correlation does not have to invent one.
     *
     * <p><strong>{@link RequestId#current()} is the middle step and it is load-bearing.</strong>
     * {@code RequestIdFilter} has already resolved an id for this request, bound it to the thread
     * and put it on the response as {@code X-Request-Id}. Minting again here would answer the
     * client one id and write another to {@code request_audit} — so the id a caller reads off its
     * own response would match no row, which is the single thing the column exists to do.
     */
    public ProxyRequest(
            String method, String path, String query, Map<String, List<String>> headers, String remoteAddress) {
        this(method, path, query, headers, remoteAddress,
                resolveId(firstHeader(headers, RequestId.HEADER)));
    }

    public ProxyRequest {
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(path, "path");
        query = query == null ? "" : query;
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
        Objects.requireNonNull(requestId, "requestId");
        // Header names are case-insensitive on the wire; a policy that looks up "Authorization"
        // must not miss a client that sent "authorization".
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Objects.requireNonNull(headers, "headers")
                .forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
    }

    /** The request target as it appeared on the request line: {@code /path} or {@code /path?query}. */
    public String target() {
        return query.isEmpty() ? path : path + "?" + query;
    }

    /** First value of {@code name} (case-insensitive), or {@code null} when the header is absent. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /**
     * A copy carrying {@code name: value}, replacing any the client sent under that name — the
     * shape {@link ApiKeyFilter} needs to put a credential on the request on its way out.
     *
     * <p>Replaces rather than appends, and that is the point: a proxy that adds a second
     * {@code Authorization} to the one already there sends both, and which one the upstream honours
     * is its business, not ours. The request id is carried over rather than re-resolved, so
     * rewriting a header cannot detach a request from the line that records it.
     *
     * <p>Header names are case-insensitive here, so this replaces a client's {@code authorization}
     * as readily as its {@code Authorization}.
     */
    public ProxyRequest withHeader(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        TreeMap<String, List<String>> replaced = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        replaced.putAll(headers);
        replaced.put(name, List.of(value));
        return new ProxyRequest(method, path, query, replaced, remoteAddress, requestId);
    }

    /** The inbound id, else the thread's, else a fresh one. See the five-argument constructor. */
    private static String resolveId(String inbound) {
        String sanitized = RequestId.sanitize(inbound);
        if (sanitized != null) {
            return sanitized;
        }
        String current = RequestId.current();
        return current == null ? RequestId.mint() : current;
    }

    /** Header lookup before the record exists, for the constructor that derives the id. */
    private static String firstHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }
}

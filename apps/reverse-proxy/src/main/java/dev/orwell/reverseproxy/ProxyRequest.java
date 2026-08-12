package dev.orwell.reverseproxy;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Framework-neutral view of an inbound request: what a {@link Policy} decides on and what
 * {@link UpstreamClient} forwards.
 *
 * <p>{@code path} and {@code query} stay in their wire (percent-encoded) form. The proxy
 * reassembles them into the upstream URI verbatim, so decoding here would corrupt any request
 * whose path carries an encoded slash, space or {@code ?}.
 *
 * <p>The body is deliberately not part of this record. A policy decides on the request line and
 * headers, which the proxy has in full before it has read a byte of the body — so a blocked
 * request costs nothing to refuse.
 */
public record ProxyRequest(
        String method,
        String path,
        String query,
        Map<String, List<String>> headers,
        String remoteAddress
) {
    public ProxyRequest {
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        Objects.requireNonNull(path, "path");
        query = query == null ? "" : query;
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
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
}

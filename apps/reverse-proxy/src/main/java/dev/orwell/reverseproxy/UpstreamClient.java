package dev.orwell.reverseproxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Forwards an admitted request to the upstream origin and hands its answer back verbatim. */
public final class UpstreamClient {
    /**
     * Hop-by-hop headers describe a single connection, not the message, so they must not be
     * relayed in either direction (RFC 9110 §7.6.1). Most of them are also on
     * {@code java.net.http}'s restricted list and would throw if set, and {@code Content-Length}
     * has to go so the servlet container can size the response it actually writes.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final String upstream;
    private final Duration timeout;
    private final HttpClient http;

    public UpstreamClient(String upstreamUrl, Duration timeout) {
        this(upstreamUrl, timeout, HttpClient.newBuilder()
                .connectTimeout(timeout)
                // A redirect is the client's to follow: rewriting Location here would hide the
                // upstream's own hostnames behind the proxy and change what the client sees.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public UpstreamClient(String upstreamUrl, Duration timeout, HttpClient http) {
        String trimmed = Objects.requireNonNull(upstreamUrl, "upstreamUrl").trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("upstreamUrl must not be blank");
        }
        String withoutTrailingSlash = trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
        URI base = URI.create(withoutTrailingSlash);
        if (base.getScheme() == null || base.getHost() == null) {
            // Fail at startup rather than turning every request into a 502.
            throw new IllegalArgumentException("upstreamUrl must be absolute, e.g. http://host:8080: "
                    + upstreamUrl);
        }
        this.upstream = withoutTrailingSlash;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = Objects.requireNonNull(http, "http");
    }

    public String upstreamUrl() {
        return upstream;
    }

    public ProxyResponse forward(ProxyRequest request, byte[] body) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(body, "body");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(upstream + request.target()))
                .timeout(timeout)
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new ProxyResponse(response.statusCode(), relayableHeaders(response), response.body());
    }

    private static Map<String, List<String>> relayableHeaders(HttpResponse<byte[]> response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, values);
            }
        });
        return headers;
    }
}

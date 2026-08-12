package dev.orwell.reverseproxy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catch-all entry point: every path is a candidate for proxying. The shared {@code GET /health}
 * is an exact mapping, which Spring prefers over this pattern, so the proxy's own health check
 * stays local instead of being forwarded upstream.
 */
@RestController
public class ProxyController {
    private final ProxyEndpoint endpoint;
    private final int maxBodyBytes;

    public ProxyController(ProxyEndpoint endpoint, @Value("${proxy.max-body-bytes:10485760}") int maxBodyBytes) {
        this.endpoint = endpoint;
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("proxy.max-body-bytes must be positive: " + maxBodyBytes);
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest servletRequest) throws IOException {
        ProxyRequest request = toProxyRequest(servletRequest);
        Optional<byte[]> body = readBody(servletRequest);
        ProxyResponse response = body
                .map(bytes -> endpoint.handle(request, bytes))
                .orElseGet(() -> endpoint.reject(request, 413, "request body too large"));
        return toResponseEntity(response);
    }

    /**
     * Reads the body into memory, since it has to be replayed on a second connection. Empty when
     * the client sent more than {@code PROXY_MAX_BODY_BYTES}: buffering an unbounded upload would
     * let one request take the proxy down.
     */
    private Optional<byte[]> readBody(HttpServletRequest request) throws IOException {
        int limit = maxBodyBytes == Integer.MAX_VALUE ? maxBodyBytes : maxBodyBytes + 1;
        try (InputStream stream = request.getInputStream()) {
            byte[] body = stream.readNBytes(limit);
            return body.length > maxBodyBytes ? Optional.empty() : Optional.of(body);
        }
    }

    private static ProxyRequest toProxyRequest(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, Collections.list(request.getHeaders(name)));
        }
        return new ProxyRequest(
                request.getMethod(),
                // Already percent-encoded, which is what the upstream URI needs.
                request.getRequestURI(),
                request.getQueryString(),
                headers,
                request.getRemoteAddr());
    }

    private static ResponseEntity<byte[]> toResponseEntity(ProxyResponse response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach(headers::addAll);
        return ResponseEntity.status(response.status()).headers(headers).body(response.body());
    }
}

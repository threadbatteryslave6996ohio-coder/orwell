package dev.orwell.reverseproxy;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the proxy sends back: an upstream response passed through, or one the proxy produced
 * itself (a policy refusal, an upstream failure).
 *
 * <p>{@code body} is the raw payload and is not copied, so the generated {@code equals} compares
 * it by identity. Assert on {@link #status()} and {@link #bodyAsString()}, not on whole responses.
 */
public record ProxyResponse(int status, Map<String, List<String>> headers, byte[] body) {
    public ProxyResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status: " + status);
        }
        // Case-insensitive for the same reason as ProxyRequest, and one concrete one: java.net.http
        // hands back lowercased names, so a lookup for "Content-Type" on a Map.copyOf would miss.
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Objects.requireNonNull(headers, "headers")
                .forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
        Objects.requireNonNull(body, "body");
    }

    /** A proxy-generated answer in the repo's {@code {"success":false,"error":…}} envelope. */
    public static ProxyResponse error(int status, String message) {
        String json = "{\"success\":false,\"error\":\"" + escape(message) + "\"}";
        return new ProxyResponse(
                status,
                Map.of("Content-Type", List.of("application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String escape(String message) {
        return String.valueOf(message).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

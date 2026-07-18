package dev.orwell.http;

import java.util.Map;
import java.util.Objects;

/** Framework-neutral HTTP outcome produced by shared endpoint logic. */
public record EndpointResponse<T>(int status, T body) {

    public EndpointResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status: " + status);
        }
        Objects.requireNonNull(body, "body");
    }

    public static <T> EndpointResponse<T> ok(T body) {
        return new EndpointResponse<>(200, body);
    }

    public static <T> EndpointResponse<T> of(int status, T body) {
        return new EndpointResponse<>(status, body);
    }

    public static EndpointResponse<Map<String, Object>> error(int status, String message) {
        return of(status, Map.of("success", false, "error", String.valueOf(message)));
    }
}

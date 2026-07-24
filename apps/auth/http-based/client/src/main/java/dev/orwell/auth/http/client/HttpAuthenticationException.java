package dev.orwell.auth.http.client;

public final class HttpAuthenticationException extends RuntimeException {
    private final Integer statusCode;

    public HttpAuthenticationException(String message) {
        this(message, (Integer) null);
    }

    public HttpAuthenticationException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public HttpAuthenticationException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    /** The HTTP status the auth server returned, or null when the failure was not an HTTP error response. */
    public Integer statusCode() {
        return statusCode;
    }
}

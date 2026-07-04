package dev.orwell.auth.http.client;

public final class HttpAuthenticationException extends RuntimeException {
    public HttpAuthenticationException(String message) {
        super(message);
    }

    public HttpAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package dev.clippy.auth.client;

public final class AuthClientException extends RuntimeException {
    public AuthClientException(String message) {
        super(message);
    }

    public AuthClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

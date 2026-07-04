package dev.orwell.env;

public final class EnvValidationException extends RuntimeException {
    public EnvValidationException(String message) {
        super(message);
    }

    public EnvValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

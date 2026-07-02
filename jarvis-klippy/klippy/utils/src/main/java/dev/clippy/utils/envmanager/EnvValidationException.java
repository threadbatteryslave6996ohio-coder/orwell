package dev.clippy.utils.envmanager;

public final class EnvValidationException extends RuntimeException {
    public EnvValidationException(String message) {
        super(message);
    }

    public EnvValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

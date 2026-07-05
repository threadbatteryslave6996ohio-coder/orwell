package dev.orwell.secrets.client;

public class SecretsManagerException extends RuntimeException {
    private final int statusCode;

    public SecretsManagerException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public SecretsManagerException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int getStatusCode() {
        return statusCode;
    }
}

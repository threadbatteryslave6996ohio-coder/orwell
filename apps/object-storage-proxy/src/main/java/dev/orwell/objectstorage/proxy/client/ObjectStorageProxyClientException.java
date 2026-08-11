package dev.orwell.objectstorage.proxy.client;

public final class ObjectStorageProxyClientException extends RuntimeException {
    private final Integer statusCode;

    public ObjectStorageProxyClientException(String message) {
        super(message);
        this.statusCode = null;
    }

    public ObjectStorageProxyClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ObjectStorageProxyClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public Integer statusCode() {
        return statusCode;
    }
}

package dev.orwell.bucket.proxy.client;

public final class BucketProxyClientException extends RuntimeException {
    private final Integer statusCode;

    public BucketProxyClientException(String message) {
        super(message);
        this.statusCode = null;
    }

    public BucketProxyClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public BucketProxyClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public Integer statusCode() {
        return statusCode;
    }
}

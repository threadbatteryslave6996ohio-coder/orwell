package dev.orwell.insta.graph;

import dev.orwell.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * PUTs pictures to a bucket over HTTP — an S3-compatible endpoint, or this repo's
 * {@code jarvis-bucket-proxy}, which fronts S3/MinIO and Azure Blob.
 *
 * <p>The optional bearer token is a plain configured value rather than an auth-server session on
 * purpose: this program dropped its auth dependency when it stopped being a server, and a
 * one-shot upload does not justify bringing a token-refresh client back.
 */
public final class HttpPictureStore implements PictureStore {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final String token;
    private final Logger logger;

    public HttpPictureStore(String baseUrl, String token, Logger logger) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.token = token;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String put(String contentHash, byte[] bytes) throws Exception {
        String key = PictureKeys.keyFor(contentHash);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + key))
                .timeout(TIMEOUT)
                .header("Content-Type", "image/jpeg")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }

        HttpResponse<Void> response =
                http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Bucket rejected the upload with HTTP " + response.statusCode() + ".");
        }
        logger.debug("Stored a profile picture.", Map.of("key", key, "bytes", bytes.length));
        return key;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}

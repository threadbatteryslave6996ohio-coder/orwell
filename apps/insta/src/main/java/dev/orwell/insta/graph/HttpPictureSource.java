package dev.orwell.insta.graph;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Downloads a picture over HTTP. Bounded so one slow CDN cannot stall a whole sync. */
public final class HttpPictureSource implements PictureSource {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    /** Profile pictures are tens of kilobytes; anything this large is not one. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public byte[] fetch(String url) throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // Expired signatures are the common case here, and they are not worth retrying.
            throw new IllegalStateException(
                    "Profile picture URL returned HTTP " + response.statusCode() + ".");
        }
        byte[] body = response.body();
        if (body.length == 0) {
            throw new IllegalStateException("Profile picture URL returned an empty body.");
        }
        if (body.length > MAX_BYTES) {
            throw new IllegalStateException("Profile picture is implausibly large: " + body.length);
        }
        return body;
    }
}

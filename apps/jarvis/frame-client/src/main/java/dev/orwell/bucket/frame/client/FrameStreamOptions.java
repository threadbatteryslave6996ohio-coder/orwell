package dev.orwell.bucket.frame.client;

import java.time.Duration;
import java.util.Objects;

/**
 * How a watcher connects to the hub.
 *
 * @param hubBaseUrl   the hub's base URL, e.g. {@code http://jarvis-hub:9001} or
 *                     {@code http://localhost:8080/hub}. {@code /frames/stream} is appended.
 * @param source       watch one camera, or null for every source.
 * @param subscription durable cursor name. With one, the hub remembers this watcher's position and
 *                     a restart resumes where it stopped; without one, a restart joins at the head
 *                     and whatever happened while it was down is lost. Name it after the watcher,
 *                     not the host — the point is that it survives the process.
 * @param from         explicit start position, or null to let the subscription decide. {@code 0L}
 *                     replays everything the hub still has.
 * @param minBackoff   first reconnect delay after the stream drops.
 * @param maxBackoff   ceiling the backoff doubles up to. A hub that is down for an hour must not be
 *                     retried every second for an hour.
 * @param verifyHash   check the hub's {@code sha256} against the bytes. On by default: it is one
 *                     hash per frame, and it catches a truncated or corrupted relay at the point
 *                     where the frame arrives rather than deep inside a decoder.
 */
public record FrameStreamOptions(
        String hubBaseUrl,
        String source,
        String subscription,
        Long from,
        Duration minBackoff,
        Duration maxBackoff,
        boolean verifyHash) {

    public FrameStreamOptions {
        Objects.requireNonNull(hubBaseUrl, "hubBaseUrl");
        Objects.requireNonNull(minBackoff, "minBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (minBackoff.isNegative() || minBackoff.isZero()) {
            throw new IllegalArgumentException("minBackoff must be positive, got " + minBackoff);
        }
        if (maxBackoff.compareTo(minBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be at least minBackoff");
        }
    }

    /** The common case: every source, a durable subscription, default backoff. */
    public static FrameStreamOptions of(String hubBaseUrl, String subscription) {
        return new FrameStreamOptions(hubBaseUrl, null, subscription, null,
                Duration.ofSeconds(1), Duration.ofSeconds(30), true);
    }

    public FrameStreamOptions withSource(String source) {
        return new FrameStreamOptions(hubBaseUrl, source, subscription, from,
                minBackoff, maxBackoff, verifyHash);
    }

    public FrameStreamOptions withFrom(Long from) {
        return new FrameStreamOptions(hubBaseUrl, source, subscription, from,
                minBackoff, maxBackoff, verifyHash);
    }

    /** The full stream URL, with whichever of the filters are set. */
    String streamUri() {
        StringBuilder uri = new StringBuilder(trimTrailingSlash(hubBaseUrl)).append("/frames/stream");
        char separator = '?';
        if (source != null && !source.isBlank()) {
            uri.append(separator).append("source=").append(encode(source));
            separator = '&';
        }
        if (subscription != null && !subscription.isBlank()) {
            uri.append(separator).append("subscription=").append(encode(subscription));
            separator = '&';
        }
        if (from != null) {
            uri.append(separator).append("from=").append(from);
        }
        return uri.toString();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}

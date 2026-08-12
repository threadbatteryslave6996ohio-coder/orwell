package dev.orwell.bucket.frame.client;

import java.time.Instant;

/**
 * One frame received from the hub, already decoded.
 *
 * <p>{@code bytes} are the frame itself — base64 has been undone and, if the hub sent a
 * {@code sha256}, checked against them. A watcher gets bytes it can hand straight to an image
 * decoder, or ignore: the hub does not require a frame to be an image, so neither does this.
 *
 * @param frameId    the hub's {@code frame_events.id}. Monotonic, and what resume is keyed on, so
 *                   a watcher that records its own position records this.
 * @param source     which camera the frame came from.
 * @param frameIndex the producer's own counter, or null if it did not send one.
 * @param capturedAt when the hub received it.
 * @param sha256     hex digest as sent by the hub, or null.
 * @param bytes      the decoded frame.
 */
public record Frame(
        long frameId,
        String source,
        Long frameIndex,
        Instant capturedAt,
        String sha256,
        byte[] bytes) {

    /** Frame bytes, for logging and for sizing decisions. */
    public int size() {
        return bytes.length;
    }
}

package dev.orwell.bucket.frame;

import dev.orwell.primitives.Sha256;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Map;

/**
 * The frame envelope {@code POST /frames} accepts: the base64 payload, its optional hash, the source
 * it came from and optionally when it was captured.
 *
 * <p>A library rather than a method on the hub's ingest service because it is also what a producer
 * validates against before pushing — {@code jarvis-frame-viewer}'s backfill builds envelopes with it.
 * It once fronted {@code POST /detect} and {@code POST /motion} as well, which is why it is a
 * separate module; both endpoints were deleted deliberately and are not coming back.
 *
 * <p>Note what is <em>not</em> validated: that the bytes are a decodable image. The hub only stores
 * and relays bytes, so it accepts whatever a producer sends — deciding whether a frame is worth
 * pushing is a producer's job.
 */
public final class FramePayload {
    private FramePayload() {
    }

    public static byte[] decode(Map<String, Object> payload) {
        Object encoded = payload.get("frameBase64");
        if (encoded == null) {
            throw new InvalidFrameException("frameBase64 missing");
        }
        byte[] frame;
        try {
            frame = Base64.getDecoder().decode(String.valueOf(encoded));
        } catch (IllegalArgumentException exception) {
            throw new InvalidFrameException("frameBase64 is not valid base64");
        }
        String expectedSha = String.valueOf(payload.getOrDefault("frameSha256", ""));
        if (!expectedSha.isBlank() && !expectedSha.equals(Sha256.hex(frame))) {
            throw new InvalidFrameException("frame hash mismatch");
        }
        return frame;
    }

    /** The stream identity a frame belongs to; {@code streamId} is the older worker's spelling. */
    public static String source(Map<String, Object> payload) {
        return String.valueOf(payload.getOrDefault("source", payload.getOrDefault("streamId", "unknown")));
    }

    /**
     * When the producer says this frame was captured, or null to let the hub stamp its arrival.
     *
     * <p>Optional, and absent for a live camera: a frame that has just been pushed was captured now,
     * and a producer's clock is a worse answer than the receiver's for that. It exists for the case
     * where "now" is simply wrong — <strong>backfill</strong>. Frames cut out of a recording that was
     * made an hour ago belong at the time they were recorded, otherwise an hour of footage lands in
     * the table as an instant and every query in the repo that is expressed in capture time (the
     * hub's own {@code GET /frames}, the retention worker's age bound) reads it wrongly.
     *
     * <p><strong>This is asserted, not verified.</strong> Nothing here can check whether a frame was
     * really captured when a producer says — the value is a claim by whoever can reach this endpoint,
     * exactly as {@code source} already is. Two consequences worth stating rather than discovering:
     * a frame dated far in the past may be deleted by the next retention sweep before anybody sees
     * it, and dating a frame in the future hides it from a {@code to}-bounded range query until then.
     * Neither is prevented, because a backfill of genuinely old footage is the point.
     *
     * <p>The row id is unaffected and still comes from the ingest sequence, so id order remains
     * arrival order and only {@code captured_at} moves. That is what keeps the stream's resume
     * ({@code Last-Event-ID}) and the viewer's back-button exact while a backfill is running.
     *
     * @throws InvalidFrameException if the field is present but not an ISO-8601 instant, so a
     *         mistyped backfill fails loudly instead of silently landing at "now".
     */
    public static Instant capturedAt(Map<String, Object> payload) {
        Object value = payload.get("capturedAt");
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException exception) {
            throw new InvalidFrameException(
                    "capturedAt must be an ISO-8601 instant, like 2026-08-12T10:00:00Z");
        }
    }

    /** A malformed frame in the request (bad/missing base64 or hash mismatch): a client error (400). */
    public static final class InvalidFrameException extends RuntimeException {
        public InvalidFrameException(String message) {
            super(message);
        }
    }
}

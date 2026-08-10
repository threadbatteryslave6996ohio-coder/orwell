package dev.orwell.bucket.detection;

import dev.orwell.primitives.Sha256;

import java.util.Base64;
import java.util.Map;

/**
 * The frame envelope shared by {@code POST /detect} and {@code POST /motion}. Both receive the
 * same body from the stream worker, so the base64/hash validation lives here rather than being
 * duplicated (and drifting) in either service.
 */
final class FramePayload {
    private FramePayload() {
    }

    static byte[] decode(Map<String, Object> payload) {
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
    static String source(Map<String, Object> payload) {
        return String.valueOf(payload.getOrDefault("source", payload.getOrDefault("streamId", "unknown")));
    }

    /** A malformed frame in the request (bad/missing base64 or hash mismatch): a client error (400). */
    static final class InvalidFrameException extends RuntimeException {
        InvalidFrameException(String message) {
            super(message);
        }
    }
}

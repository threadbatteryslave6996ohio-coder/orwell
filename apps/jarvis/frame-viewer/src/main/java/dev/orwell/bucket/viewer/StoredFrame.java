package dev.orwell.bucket.viewer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One frame's metadata — everything about a stored frame except the frame.
 *
 * <p><strong>The bytes are deliberately absent.</strong> The hub's {@code GET /frames} answers with
 * {@code frameBase64} inline, which is right for a caller that wants a window in one document but
 * wrong for a viewer: base64 inflates a 40 KB frame to ~55 KB, it cannot be cached by the browser
 * independently of the metadata around it, and stepping through history one frame at a time would
 * re-encode and re-download the same images. So a navigation step here answers with this — a few
 * hundred bytes — and names an {@code imageUrl} the browser fetches, caches and reuses.
 *
 * @param bytes the stored frame's size, from {@code octet_length}, which Postgres answers from the
 *              TOAST pointer without reading the frame back out. It is metadata, not a payload.
 */
public record StoredFrame(
        long frameId,
        String source,
        Long frameIndex,
        String sha256,
        Instant capturedAt,
        long bytes) {

    /** Where the bytes for this frame are served from. */
    public String imageUrl() {
        return "api/frames/" + frameId + "/image";
    }

    /**
     * The wire form.
     *
     * <p>Field names match the hub's {@code FrameMessage} wherever they overlap — {@code frameId},
     * {@code source}, {@code frameIndex}, {@code capturedAt}, {@code sha256} — so anything written
     * against one reads the other. {@code frameBase64} is replaced by {@code imageUrl} plus
     * {@code bytes}, which is the whole difference between the two shapes.
     *
     * <p>{@code capturedAt} is rendered here rather than left as an {@code Instant} for Jackson to
     * decide about: without the JSR-310 module on the classpath it would silently serialize as a
     * number, and the hub sends an ISO-8601 string.
     */
    public Map<String, Object> asJson() {
        // LinkedHashMap, not Map.of: frameIndex is optional in the payload a producer pushed and is
        // therefore nullable here, which Map.of rejects with an NPE.
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("frameId", frameId);
        json.put("source", source);
        json.put("frameIndex", frameIndex);
        json.put("capturedAt", capturedAt == null ? null : capturedAt.toString());
        json.put("sha256", sha256);
        json.put("bytes", bytes);
        json.put("imageUrl", imageUrl());
        return json;
    }
}

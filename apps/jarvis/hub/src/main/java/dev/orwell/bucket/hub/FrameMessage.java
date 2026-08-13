package dev.orwell.bucket.hub;

import dev.orwell.bucket.hub.entity.FrameEventEntity;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wire form of a frame, shared by the live stream, the replay path and the range query.
 *
 * <p>One definition on purpose: {@code jarvis-frame-client} decodes these fields by name, so a
 * frame fetched from {@code GET /frames} must look exactly like the same frame delivered over SSE.
 * When this lived as a private method on {@link FrameHub} a second reader could only copy it, and a
 * copy is what drifts.
 */
final class FrameMessage {
    private FrameMessage() {
    }

    static Map<String, Object> of(FrameEventEntity frame) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("frameId", frame.getId());
        message.put("source", frame.getSource());
        message.put("frameIndex", frame.getFrameIndex());
        message.put("capturedAt", frame.getCapturedAt().toString());
        message.put("sha256", frame.getSha256());
        message.put("frameBase64", Base64.getEncoder().encodeToString(frame.getFrameBytes()));
        return message;
    }
}

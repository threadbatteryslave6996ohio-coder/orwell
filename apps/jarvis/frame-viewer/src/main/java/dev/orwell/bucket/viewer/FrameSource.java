package dev.orwell.bucket.viewer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One device that has pushed frames the hub still holds — a row of the picker.
 *
 * <p>There is no registry of cameras anywhere in jarvis: a producer names itself in the
 * {@code source} field of each frame it pushes, and the hub stores whatever it is told. So the list
 * of devices is derived from the frames themselves, which has a consequence worth stating — a camera
 * whose frames have all aged out of retention disappears from the picker, because as far as the
 * table is concerned it no longer exists.
 *
 * @param frames   how many of this source's frames are still stored
 * @param oldestId the lowest frame id retained, which is where stepping back eventually stops
 * @param newestId the highest, which is where the viewer opens
 */
public record FrameSource(
        String source,
        long frames,
        long oldestId,
        long newestId,
        Instant oldestCapturedAt,
        Instant newestCapturedAt) {

    public Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("source", source);
        json.put("frames", frames);
        json.put("oldestFrameId", oldestId);
        json.put("newestFrameId", newestId);
        json.put("oldestCapturedAt", oldestCapturedAt == null ? null : oldestCapturedAt.toString());
        json.put("newestCapturedAt", newestCapturedAt == null ? null : newestCapturedAt.toString());
        return json;
    }
}

package dev.orwell.bucket.retention;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one sweep did. Returned rather than only logged so a caller can put the numbers on its own
 * health endpoint.
 *
 * @param rowsDroppedByAge   rows deleted for being older than the age bound.
 * @param rowsDroppedByBytes rows deleted to bring the payload back under budget.
 * @param bytesBefore        retained payload bytes before the sweep.
 * @param bytesAfter         retained payload bytes after it.
 * @param overBudget         whether the byte budget was exceeded when the sweep started.
 */
public record SweepResult(
        long rowsDroppedByAge,
        long rowsDroppedByBytes,
        long bytesBefore,
        long bytesAfter,
        boolean overBudget) {

    static SweepResult unchanged(long bytes) {
        return new SweepResult(0, 0, bytes, bytes, false);
    }

    public long rowsDropped() {
        return rowsDroppedByAge + rowsDroppedByBytes;
    }

    /** Shape for a log metadata map or a health payload. */
    public Map<String, Object> asMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("rowsDroppedByAge", rowsDroppedByAge);
        metadata.put("rowsDroppedByBytes", rowsDroppedByBytes);
        metadata.put("bytesBefore", bytesBefore);
        metadata.put("bytesAfter", bytesAfter);
        metadata.put("overBudget", overBudget);
        return metadata;
    }
}

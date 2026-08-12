package dev.orwell.bucket.hub;

import dev.orwell.bucket.frame.FramePayload;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingest behavior and health details, kept out of the controller so the transport stays thin.
 *
 * <p>Smaller than the endpoint it was carved out of in three ways: it no longer dispatches to
 * person detection and motion, it no longer has a null-relay branch, and it answers with Spring's
 * {@code ResponseEntity} rather than the engine-neutral {@code EndpointResponse}. All three existed
 * because one class fronted three unrelated services across two engines. The hub is Spring-only, so
 * neutrality here would cost a dependency on {@code undertow-bootstrap} — a whole HTTP server —
 * to buy portability it cannot use. The error body is unchanged:
 * {@code {"success": false, "error": "..."}}.
 */
public final class HubEndpoint {
    private final FrameIngestService ingestService;
    private final FrameHub hub;
    private final FrameStoreWriter store;

    HubEndpoint(FrameIngestService ingestService, FrameHub hub, FrameStoreWriter store) {
        this.ingestService = ingestService;
        this.hub = hub;
        this.store = store;
    }

    ResponseEntity<Map<String, Object>> frames(Map<String, Object> payload) {
        try {
            return ResponseEntity.ok(ingestService.ingest(payload));
        } catch (FramePayload.InvalidFrameException exception) {
            return error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            return error(500, exception.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("success", false, "error", String.valueOf(message)));
    }

    /**
     * What an operator needs to see about the hub.
     *
     * <p>The retention counters that used to appear here — retained bytes, rows dropped, last
     * sweep error — moved out with the sweep itself and are now reported by
     * {@code jarvis-retention-worker} to the log. The hub can still say how much it is holding in
     * flight, but it is no longer the thing that decides how much it may hold.
     */
    Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("framesReceivedTotal", ingestService.framesReceivedTotal());
        details.put("connectedClients", hub.connectedCount());
        details.put("framesDistributedTotal", hub.framesDistributedTotal());
        details.put("framesReplayedTotal", hub.framesReplayedTotal());
        details.put("framesDroppedTotal", hub.framesDroppedTotal());
        details.put("framesPendingWrite", store.pendingWrites());
        // Frames that went out live but never made it to the store, so cannot be replayed.
        details.put("framesUnstoredTotal", store.framesUnstoredTotal());
        return details;
    }
}

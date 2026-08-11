package dev.orwell.bucket.detection;

import dev.orwell.http.EndpointResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Server-independent detection endpoint behavior shared by Spring and Undertow. */
final class DetectionEndpoint {
    private final DetectionService service;
    private final MotionService motionService;
    /**
     * Null on the Undertow engine. The relay hands frames to open SSE connections, which is Spring
     * MVC async plumbing the Undertow runtime does not set up — so rather than pretend,
     * {@code /frames} answers 501 there and says which engine it needs.
     */
    private final FrameIngestService ingestService;
    /** Null alongside {@link #ingestService}; only read when the relay is available. */
    private final FrameHub hub;
    private final FrameStoreWriter store;
    private final FrameRetentionJob retention;

    DetectionEndpoint(DetectionService service, MotionService motionService) {
        this(service, motionService, null, null, null, null);
    }

    DetectionEndpoint(DetectionService service, MotionService motionService,
            FrameIngestService ingestService, FrameHub hub, FrameStoreWriter store,
            FrameRetentionJob retention) {
        this.service = service;
        this.motionService = motionService;
        this.ingestService = ingestService;
        this.hub = hub;
        this.store = store;
        this.retention = retention;
    }

    EndpointResponse<Map<String, Object>> detect(Map<String, Object> payload) {
        return run(() -> service.detect(payload));
    }

    EndpointResponse<Map<String, Object>> motion(Map<String, Object> payload) {
        return run(() -> motionService.motion(payload));
    }

    EndpointResponse<Map<String, Object>> frames(Map<String, Object> payload) {
        if (ingestService == null) {
            return EndpointResponse.error(501,
                    "the frame relay requires SERVER_ENGINE=spring; this process runs undertow");
        }
        return run(() -> ingestService.ingest(payload));
    }

    private static EndpointResponse<Map<String, Object>> run(Supplier<Map<String, Object>> action) {
        try {
            return EndpointResponse.ok(action.get());
        } catch (FramePayload.InvalidFrameException exception) {
            return EndpointResponse.error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            return EndpointResponse.error(500, exception.getMessage());
        }
    }

    Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("detectionsTotal", service.detectionsTotal());
        details.put("alertsSentTotal", service.alertsSentTotal());
        details.put("framesComparedTotal", motionService.framesComparedTotal());
        details.put("changesDetectedTotal", motionService.changesDetectedTotal());
        details.put("relayAvailable", ingestService != null);
        if (ingestService != null) {
            details.put("framesReceivedTotal", ingestService.framesReceivedTotal());
            details.put("connectedClients", hub.connectedCount());
            details.put("framesDistributedTotal", hub.framesDistributedTotal());
            details.put("framesReplayedTotal", hub.framesReplayedTotal());
            details.put("framesDroppedTotal", hub.framesDroppedTotal());
            details.put("framesPendingWrite", store.pendingWrites());
            details.put("retainedFrameBytes", retention.retainedBytes());
            details.put("framesDroppedByAgeTotal", retention.framesDroppedByAgeTotal());
            details.put("framesDroppedByBudgetTotal", retention.framesDroppedByBudgetTotal());
            // Null unless the last sweep threw. A sweep that stops working is how the table grows
            // without bound, so it is reported rather than left in the log.
            details.put("lastRetentionSweepError", retention.lastSweepError());
            // Frames that went out live but never made it to the store, so cannot be replayed.
            details.put("framesUnstoredTotal", store.framesUnstoredTotal());
        }
        return details;
    }
}

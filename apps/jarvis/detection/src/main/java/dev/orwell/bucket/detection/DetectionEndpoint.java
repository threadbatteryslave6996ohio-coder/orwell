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
     * Null on the Undertow engine. Frame fan-out is Postgres-backed and driven by scheduled Spring
     * beans, none of which exist in the Undertow runtime — so rather than pretend, {@code /frames}
     * answers 501 there and says which engine it needs.
     */
    private final FrameIngestService ingestService;

    DetectionEndpoint(DetectionService service, MotionService motionService) {
        this(service, motionService, null);
    }

    DetectionEndpoint(DetectionService service, MotionService motionService,
            FrameIngestService ingestService) {
        this.service = service;
        this.motionService = motionService;
        this.ingestService = ingestService;
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
                    "frame fan-out requires SERVER_ENGINE=spring; this process runs undertow");
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
        details.put("fanoutAvailable", ingestService != null);
        if (ingestService != null) {
            details.put("framesReceivedTotal", ingestService.framesReceivedTotal());
            details.put("framesStoredTotal", ingestService.framesStoredTotal());
        }
        return details;
    }
}

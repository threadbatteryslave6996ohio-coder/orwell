package dev.orwell.bucket.detection;

import dev.orwell.http.EndpointResponse;

import java.util.Map;

/** Server-independent detection endpoint behavior shared by Spring and Undertow. */
final class DetectionEndpoint {
    private final DetectionService service;

    DetectionEndpoint(DetectionService service) {
        this.service = service;
    }

    EndpointResponse<Map<String, Object>> detect(Map<String, Object> payload) {
        try {
            return EndpointResponse.ok(service.detect(payload));
        } catch (DetectionService.InvalidFrameException exception) {
            return EndpointResponse.error(400, exception.getMessage());
        } catch (RuntimeException exception) {
            return EndpointResponse.error(500, exception.getMessage());
        }
    }

    Map<String, Object> healthDetails() {
        return Map.of(
                "detectionsTotal", service.detectionsTotal(),
                "alertsSentTotal", service.alertsSentTotal()
        );
    }
}

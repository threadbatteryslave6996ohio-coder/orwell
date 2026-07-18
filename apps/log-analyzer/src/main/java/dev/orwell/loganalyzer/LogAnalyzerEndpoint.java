package dev.orwell.loganalyzer;

import dev.orwell.http.EndpointResponse;

import java.util.Map;

/** Server-independent log-analyzer endpoint behavior shared by Spring and Undertow. */
final class LogAnalyzerEndpoint {
    private final LogAnalyzerService service;

    LogAnalyzerEndpoint(LogAnalyzerService service) {
        this.service = service;
    }

    EndpointResponse<Map<String, Object>> runOnce() {
        try {
            Map<String, Object> result = service.pollOnce();
            // A poll already in progress is not a completed run; callers can retry a 409.
            return EndpointResponse.of(Boolean.TRUE.equals(result.get("skipped")) ? 409 : 200, result);
        } catch (Exception exception) {
            service.recordRunOnceFailure(exception);
            return EndpointResponse.error(500, exception.getMessage());
        }
    }

    Map<String, Object> healthDetails() {
        return service.healthDetails();
    }
}

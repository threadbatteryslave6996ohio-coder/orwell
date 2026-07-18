package dev.orwell.alerting;

import dev.orwell.http.EndpointResponse;

import java.util.Map;

/** Server-independent alerting endpoint behavior shared by Spring and Undertow. */
final class AlertEndpoint {
    private final AlertService service;

    AlertEndpoint(AlertService service) {
        this.service = service;
    }

    EndpointResponse<Map<String, Object>> alert(Map<String, Object> body) {
        return EndpointResponse.ok(service.handleAlert(body));
    }

    Map<String, Object> healthDetails() {
        return Map.of("emailEnabled", service.emailEnabled());
    }
}

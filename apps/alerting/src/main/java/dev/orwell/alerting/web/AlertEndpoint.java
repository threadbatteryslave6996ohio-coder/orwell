package dev.orwell.alerting.web;

import dev.orwell.alerting.service.AlertService;
import dev.orwell.http.EndpointResponse;

import java.util.Map;

/** Server-independent alerting endpoint behavior shared by Spring and Undertow. */
public final class AlertEndpoint {
    private final AlertService service;

    public AlertEndpoint(AlertService service) {
        this.service = service;
    }

    public EndpointResponse<Map<String, Object>> alert(Map<String, Object> body) {
        return EndpointResponse.ok(service.handleAlert(body));
    }

    public Map<String, Object> healthDetails() {
        return Map.of("emailEnabled", service.emailEnabled());
    }
}

package dev.orwell.bootstrap.health;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared {@code GET /health} endpoint for every server app. Returns the common
 * {@code {"success":true,"status":"healthy"}} envelope merged with the details of every
 * {@link HealthDetailsProvider} bean the app contributes (none required).
 */
@RestController
public class SharedHealthController {
    private final ObjectProvider<HealthDetailsProvider> detailsProviders;

    public SharedHealthController(ObjectProvider<HealthDetailsProvider> detailsProviders) {
        this.detailsProviders = detailsProviders;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("status", "healthy");
        detailsProviders.orderedStream().forEach(provider -> response.putAll(provider.details()));
        return response;
    }
}

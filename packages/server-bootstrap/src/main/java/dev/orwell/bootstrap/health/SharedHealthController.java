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
 *
 * <p>{@code engine} is stamped here rather than by each app, mirroring what
 * {@code UndertowHttp.health} does on the other engine. Three apps used to add it themselves, in
 * three identical {@link HealthDetailsProvider} beans that copied the details map only to put one
 * key in it — and the apps that forgot were silently unidentifiable. Which engine served a request
 * is not an app's fact to report; it is the runtime's, and this class only exists in the Spring
 * one, so it can answer for itself. It goes on last for the same reason it does on the Undertow
 * side: a provider must not be able to claim a different engine than the one that ran it.
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
        response.put("engine", "spring");
        return response;
    }
}

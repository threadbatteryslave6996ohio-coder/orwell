package dev.orwell.secrets.controller;

import dev.orwell.bootstrap.HealthDetailsProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("${secrets.route-prefix:}")
public class SecretsHealthController {
    private final HealthDetailsProvider healthDetailsProvider;

    public SecretsHealthController(@Qualifier("secretsHealthDetailsProvider") HealthDetailsProvider healthDetailsProvider) {
        this.healthDetailsProvider = healthDetailsProvider;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("status", "healthy");
        response.putAll(healthDetailsProvider.details());
        return response;
    }
}

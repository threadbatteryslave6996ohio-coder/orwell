package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.HealthDetailsProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("${jarvis.server.route-prefix:}")
public class ProxyHealthController {
    private final HealthDetailsProvider healthDetailsProvider;

    public ProxyHealthController(@Qualifier("jarvisHealthDetailsProvider") HealthDetailsProvider healthDetailsProvider) {
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

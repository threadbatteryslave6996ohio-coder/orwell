package dev.orwell.bucket.detection;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.bootstrap.HealthDetailsProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Person-detection server. Exposes {@code POST /detect} which runs person detection over a
 * base64-encoded frame and fires a cooldown-gated alert to the configured alert endpoint.
 */
@SpringBootApplication
public class DetectionApplication {
    public static final AppServer SERVER =
            new AppServer(DetectionApplication.class, "detection", DetectionEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    /** Detection counters surfaced on the shared {@code /health} endpoint. */
    @Bean
    public HealthDetailsProvider detectionHealthDetailsProvider(DetectionService service) {
        return () -> {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("detectionsTotal", service.detectionsTotal());
            details.put("alertsSentTotal", service.alertsSentTotal());
            return details;
        };
    }
}

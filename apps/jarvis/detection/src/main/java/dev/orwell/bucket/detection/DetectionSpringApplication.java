package dev.orwell.bucket.detection;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;

@SpringBootApplication(proxyBeanMethods = false)
class DetectionSpringApplication {
    private static final AppServer SERVER =
            new AppServer(DetectionSpringApplication.class, "detection", DetectionEnvs.ENV);

    DetectionSpringApplication() {
    }

    static void start(Env env) {
        SERVER.start(env);
    }

    @Bean
    DetectionEndpoint detectionEndpoint(DetectionService service) {
        return new DetectionEndpoint(service);
    }

    @Bean
    HealthDetailsProvider detectionHealthDetailsProvider(DetectionEndpoint endpoint) {
        return () -> {
            var details = new LinkedHashMap<>(endpoint.healthDetails());
            details.put("engine", "spring");
            return details;
        };
    }
}

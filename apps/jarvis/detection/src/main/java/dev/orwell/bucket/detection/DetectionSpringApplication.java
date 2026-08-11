package dev.orwell.bucket.detection;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.LinkedHashMap;

// Scheduling drives FrameRetentionJob, which is what bounds the stored frame log.
@EnableScheduling
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
    DetectionEndpoint detectionEndpoint(DetectionService service, MotionService motionService,
            FrameIngestService ingestService, FrameHub hub, FrameStoreWriter store,
            FrameRetentionJob retention) {
        return new DetectionEndpoint(service, motionService, ingestService, hub, store, retention);
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

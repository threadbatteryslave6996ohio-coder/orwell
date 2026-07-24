package dev.orwell.liveness;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.LinkedHashMap;

/**
 * Liveness analyzer. Polls Grafana Loki for client heartbeat lines on a schedule and raises a
 * cooldown-gated alert when a client the operator expects to be running stops beating.
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
public class LivenessAnalyzerApplication {
    public static final AppServer SERVER =
            new AppServer(LivenessAnalyzerApplication.class, "liveness-analyzer", LivenessAnalyzerEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    @Bean
    HealthDetailsProvider livenessHealthDetailsProvider(LivenessService service) {
        return () -> {
            var details = new LinkedHashMap<>(service.healthDetails());
            details.put("engine", "spring");
            return details;
        };
    }
}

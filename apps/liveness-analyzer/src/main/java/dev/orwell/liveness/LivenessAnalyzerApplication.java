package dev.orwell.liveness;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    /** {@code engine} is stamped by {@code SharedHealthController}, not here. */
    @Bean
    HealthDetailsProvider livenessHealthDetailsProvider(LivenessService service) {
        return service::healthDetails;
    }
}

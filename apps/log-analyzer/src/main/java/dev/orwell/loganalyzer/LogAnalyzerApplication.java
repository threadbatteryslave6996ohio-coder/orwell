package dev.orwell.loganalyzer;

import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.bootstrap.health.HealthDetailsProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Log-analyzer server. Polls Grafana Loki on a schedule (and on demand via {@code POST /run-once}),
 * classifies error logs with an AI model, and forwards cooldown-gated alerts.
 */
@SpringBootApplication
@EnableScheduling
public class LogAnalyzerApplication {
    public static final AppServer SERVER =
            new AppServer(LogAnalyzerApplication.class, "log-analyzer", LogAnalyzerEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    /** Poll/alert metrics surfaced on the shared {@code /health} endpoint. */
    @Bean
    public HealthDetailsProvider logAnalyzerHealthDetailsProvider(LogAnalyzerService service) {
        return service::healthDetails;
    }
}

package dev.orwell.loganalyzer;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.bootstrap.HealthDetailsProvider;
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
    public static final AppServer SERVER = AppServer.spring(LogAnalyzerApplication.class)
            .name("log-analyzer")
            .envs(LogAnalyzerEnvs.ENV)
            .properties(LogAnalyzerEnvs::springProperties)
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    /** Poll/alert metrics surfaced on the shared {@code /health} endpoint. */
    @Bean
    public HealthDetailsProvider logAnalyzerHealthDetailsProvider(LogAnalyzerService service) {
        return service::healthDetails;
    }
}

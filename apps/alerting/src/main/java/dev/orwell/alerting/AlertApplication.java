package dev.orwell.alerting;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.bootstrap.HealthDetailsProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Alert-relay server. Exposes {@code POST /alerts} which records the alert and, when email is
 * configured, forwards it over SMTP.
 */
@SpringBootApplication
public class AlertApplication {
    public static final AppServer SERVER = AppServer.spring(AlertApplication.class)
            .name("alerting")
            .envs(AlertEnvs.ENV)
            .properties(AlertEnvs::springProperties)
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    /** Email configuration state surfaced on the shared {@code /health} endpoint. */
    @Bean
    public HealthDetailsProvider alertHealthDetailsProvider(AlertService service) {
        return () -> Map.of("emailEnabled", service.emailEnabled());
    }
}

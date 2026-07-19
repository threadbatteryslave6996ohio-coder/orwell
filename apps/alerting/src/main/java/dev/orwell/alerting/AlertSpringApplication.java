package dev.orwell.alerting;

import dev.orwell.alerting.config.AlertEnvs;
import dev.orwell.alerting.service.AlertService;
import dev.orwell.alerting.web.AlertEndpoint;
import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;

@SpringBootApplication(proxyBeanMethods = false)
class AlertSpringApplication {
    private static final AppServer SERVER =
            new AppServer(AlertSpringApplication.class, "alerting", AlertEnvs.ENV);

    AlertSpringApplication() {
    }

    static void start(Env env) {
        SERVER.start(env);
    }

    @Bean
    AlertEndpoint alertEndpoint(AlertService service) {
        return new AlertEndpoint(service);
    }

    @Bean
    HealthDetailsProvider alertHealthDetailsProvider(AlertEndpoint endpoint) {
        return () -> {
            var details = new LinkedHashMap<>(endpoint.healthDetails());
            details.put("engine", "spring");
            return details;
        };
    }
}

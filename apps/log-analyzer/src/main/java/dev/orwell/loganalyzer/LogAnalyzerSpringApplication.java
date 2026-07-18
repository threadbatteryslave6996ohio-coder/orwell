package dev.orwell.loganalyzer;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.LinkedHashMap;

@SpringBootApplication(proxyBeanMethods = false)
@EnableScheduling
class LogAnalyzerSpringApplication {
    private static final AppServer SERVER =
            new AppServer(LogAnalyzerSpringApplication.class, "log-analyzer", LogAnalyzerEnvs.ENV);

    LogAnalyzerSpringApplication() {
    }

    static void start(Env env) {
        SERVER.start(env);
    }

    @Bean
    LogAnalyzerEndpoint logAnalyzerEndpoint(LogAnalyzerService service) {
        return new LogAnalyzerEndpoint(service);
    }

    @Bean
    HealthDetailsProvider logAnalyzerHealthDetailsProvider(LogAnalyzerEndpoint endpoint) {
        return () -> {
            var details = new LinkedHashMap<>(endpoint.healthDetails());
            details.put("engine", "spring");
            return details;
        };
    }
}

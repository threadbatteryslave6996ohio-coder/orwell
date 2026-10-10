package dev.orwell.loganalyzer;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    /** {@code engine} is stamped by {@code SharedHealthController}, not here. */
    @Bean
    HealthDetailsProvider logAnalyzerHealthDetailsProvider(LogAnalyzerEndpoint endpoint) {
        return endpoint::healthDetails;
    }
}

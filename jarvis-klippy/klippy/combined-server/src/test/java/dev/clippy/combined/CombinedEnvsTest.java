package dev.clippy.combined;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CombinedEnvsTest {
    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        var env = CombinedEnvs.from(validEnvironment());

        assertThat(CombinedEnvs.springProperties(env))
                .containsEntry("server.port", "9090")
                .containsEntry("clippy.auth.datasource.url", "jdbc:postgresql://database/auth")
                .containsEntry("spring.datasource.url", "jdbc:postgresql://database/clippy")
                .containsEntry("clippy.auth.route-prefix", "/auth")
                .containsEntry("clippy.server.route-prefix", "/api")
                .containsEntry("logging.file.name", "logs/clippy-combined-server.log")
                .containsEntry("clippy.auth.jpa.hibernate.ddl-auto", "update")
                .containsEntry("clippy.clipboard.jpa.hibernate.ddl-auto", "validate")
                .containsEntry("clippy.jpa.jdbc-time-zone", "UTC");
    }

    @Test
    void rejectsConfigurationThatTheLauncherDidNotSupply() {
        Map<String, String> incomplete = new HashMap<>(validEnvironment());
        incomplete.remove("SPRING_DATASOURCE_URL");

        assertThatThrownBy(() -> CombinedEnvs.from(incomplete))
                .hasMessage("Missing required environment variable: SPRING_DATASOURCE_URL");
    }

    @Test
    void coreHasNoPackagedApplicationDefaults() {
        assertThat(new ClassPathResource("application.yml").exists()).isFalse();
    }

    @Test
    void optionalDiagnosticLogCannotPreventStartup() throws Exception {
        Path nonDirectory = Files.createTempFile("combined-server-log-parent", ".tmp");
        String originalDirectory = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", nonDirectory.toString());
        try {
            CombinedServerApplication.logCombinedModeDisclaimer();
        } finally {
            if (originalDirectory == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalDirectory);
            }
        }
    }

    private static Map<String, String> validEnvironment() {
        return Map.ofEntries(
                Map.entry("COMBINED_SERVER_PORT", "9090"),
                Map.entry("AUTH_DATASOURCE_URL", "jdbc:postgresql://database/auth"),
                Map.entry("AUTH_DATASOURCE_USERNAME", "auth-user"),
                Map.entry("AUTH_DATASOURCE_PASSWORD", "auth-password"),
                Map.entry("SPRING_DATASOURCE_URL", "jdbc:postgresql://database/clippy"),
                Map.entry("SPRING_DATASOURCE_USERNAME", "clippy-user"),
                Map.entry("SPRING_DATASOURCE_PASSWORD", "clippy-password"),
                Map.entry("CLIPPY_AUTH_BASE_URL", "http://auth"),
                Map.entry("CLIPPY_AUTH_ROUTE_PREFIX", "/auth"),
                Map.entry("CLIPPY_SERVER_ROUTE_PREFIX", "/api"),
                Map.entry("LOGGING_FILE_NAME", "logs/clippy-combined-server.log"),
                Map.entry("AUTH_JPA_HIBERNATE_DDL_AUTO", "update"),
                Map.entry("CLIPBOARD_JPA_HIBERNATE_DDL_AUTO", "validate"),
                Map.entry("JPA_JDBC_TIME_ZONE", "UTC")
        );
    }
}

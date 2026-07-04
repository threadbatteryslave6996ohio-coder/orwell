package dev.orwell.combined;

import org.junit.jupiter.api.Test;

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
                .containsEntry("clippy.server.route-prefix", "/klippy")
                .containsEntry("jarvis.server.route-prefix", "/jarvis")
                .containsEntry("keeboarder.server.route-prefix", "/keeboarder")
                .containsEntry("logging.file.name", "logs/combined-server.log")
                .containsEntry("clippy.auth.jpa.hibernate.ddl-auto", "update")
                .containsEntry("clippy.clipboard.jpa.hibernate.ddl-auto", "validate")
                .containsEntry("clippy.jpa.jdbc-time-zone", "UTC");
    }

    @Test
    void mapsJarvisEnvironmentFileValuesToSpringProperties() {
        var env = CombinedEnvs.from(validEnvironment());

        assertThat(CombinedEnvs.springProperties(env, Map.of(
                "PROXY_STORAGE_PROVIDER", "azure",
                "AZURE_STORAGE_CONTAINER", "recordings"
        )))
                .containsEntry("proxy.storage.provider", "azure")
                .containsEntry("proxy.azure.container-name", "recordings");
    }

    @Test
    void mapsKeeboarderRuntimeValuesToSpringProperties() {
        var env = CombinedEnvs.from(validEnvironment());

        assertThat(CombinedEnvs.springProperties(env, Map.of(
                "WEBSOCKET_PORT", "9000",
                "REDIS_HOST", "redis.internal"
        )))
                .containsEntry("keeboarder.websocket.port", "9000")
                .containsEntry("keeboarder.redis.host", "redis.internal");
    }

    @Test
    void rejectsConfigurationThatTheLauncherDidNotSupply() {
        Map<String, String> incomplete = new HashMap<>(validEnvironment());
        incomplete.remove("SPRING_DATASOURCE_URL");

        assertThatThrownBy(() -> CombinedEnvs.from(incomplete))
                .hasMessage("Missing required environment variable: SPRING_DATASOURCE_URL");
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
                Map.entry("CLIPPY_SERVER_ROUTE_PREFIX", "/klippy"),
                Map.entry("LOGGING_FILE_NAME", "logs/combined-server.log"),
                Map.entry("AUTH_JPA_HIBERNATE_DDL_AUTO", "update"),
                Map.entry("CLIPBOARD_JPA_HIBERNATE_DDL_AUTO", "validate"),
                Map.entry("JPA_JDBC_TIME_ZONE", "UTC")
        );
    }
}

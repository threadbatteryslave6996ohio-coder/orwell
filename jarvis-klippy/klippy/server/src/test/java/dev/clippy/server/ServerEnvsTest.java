package dev.clippy.server;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerEnvsTest {
    @Test
    void rejectsConfigurationThatTheLauncherDidNotSupply() {
        assertThrows(EnvValidationException.class, () -> ServerEnvs.from(Map.of()));
    }

    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = ServerEnvs.from(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://database/clippy",
                "SPRING_DATASOURCE_USERNAME", "user",
                "SPRING_DATASOURCE_PASSWORD", "password",
                "SERVER_PORT", "9090",
                "CLIPPY_AUTH_BASE_URL", "http://auth",
                "LOGGING_FILE_NAME", "/tmp/clippy.log",
                "SPRING_JPA_HIBERNATE_DDL_AUTO", "validate",
                "SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE", "UTC"
        ));

        assertEquals(Map.of(
                "spring.datasource.url", "jdbc:postgresql://database/clippy",
                "spring.datasource.username", "user",
                "spring.datasource.password", "password",
                "server.port", "9090",
                "clippy.auth.base-url", "http://auth",
                "logging.file.name", "/tmp/clippy.log",
                "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC"
        ), ServerEnvs.springProperties(env));
    }
}

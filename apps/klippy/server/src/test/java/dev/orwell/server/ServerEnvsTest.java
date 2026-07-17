package dev.orwell.server;

import dev.orwell.env.Env;
import dev.orwell.env.EnvValidationException;
import dev.orwell.server.config.ServerEnvs;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerEnvsTest {
    @Test
    void rejectsConfigurationThatTheLauncherDidNotSupply() {
        assertThrows(EnvValidationException.class, () -> ServerEnvs.ENV.schema().from(Map.of()));
    }

    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = ServerEnvs.ENV.schema().from(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://database/klippy",
                "SPRING_DATASOURCE_USERNAME", "user",
                "SPRING_DATASOURCE_PASSWORD", "password",
                "SERVER_PORT", "9090",
                "SERVER_ADDRESS", "0.0.0.0",
                "AUTH_BASE_URL", "http://auth",
                "LOGGING_FILE_NAME", "/tmp/klippy.log",
                "SPRING_JPA_HIBERNATE_DDL_AUTO", "validate",
                "SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE", "UTC"
        ));

        assertEquals(Map.of(
                "spring.datasource.url", "jdbc:postgresql://database/klippy",
                "spring.datasource.username", "user",
                "spring.datasource.password", "password",
                "server.address", "0.0.0.0",
                "server.port", 9090,
                "orwell.auth.base-url", "http://auth",
                "logging.file.name", "/tmp/klippy.log",
                "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC"
        ), ServerEnvs.ENV.springProperties(env));
    }
}

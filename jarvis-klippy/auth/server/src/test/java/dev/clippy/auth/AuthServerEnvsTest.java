package dev.clippy.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthServerEnvsTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsConfigurationThatTheLauncherDidNotSupply() {
        assertThrows(EnvValidationException.class, () -> AuthServerEnvs.from(Map.of()));
    }

    @Test
    void launcherResolvesDotenvFromAncestralDirectory() throws IOException {
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/child"));
        Files.writeString(
                tempDir.resolve(".env"),
                "AUTH_DATASOURCE_URL=jdbc:postgresql://example:5432/auth\n" +
                        "AUTH_DATASOURCE_USERNAME=example-user\n" +
                        "AUTH_DATASOURCE_PASSWORD=example-pass\n" +
                        "AUTH_SERVER_PORT=9090\n" +
                        "AUTH_LOGGING_FILE_NAME=/tmp/auth.log\n" +
                        "AUTH_JPA_HIBERNATE_DDL_AUTO=validate\n" +
                        "AUTH_JPA_JDBC_TIME_ZONE=UTC\n"
        );

        Env env = AuthServerEnvs.from(ClippyAuthServerLauncher.resolveEnvironment(nestedDirectory));

        assertEquals("jdbc:postgresql://example:5432/auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_URL));
        assertEquals("example-user", env.get(AuthServerEnvs.AUTH_DATASOURCE_USERNAME));
        assertEquals("example-pass", env.get(AuthServerEnvs.AUTH_DATASOURCE_PASSWORD));
        assertEquals("9090", env.get(AuthServerEnvs.AUTH_SERVER_PORT));
        assertEquals("/tmp/auth.log", env.get(AuthServerEnvs.AUTH_LOGGING_FILE_NAME));
    }

    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = AuthServerEnvs.from(Map.of(
                "AUTH_DATASOURCE_URL", "jdbc:postgresql://database/auth",
                "AUTH_DATASOURCE_USERNAME", "user",
                "AUTH_DATASOURCE_PASSWORD", "password",
                "AUTH_SERVER_PORT", "9091",
                "AUTH_LOGGING_FILE_NAME", "/tmp/auth.log",
                "AUTH_JPA_HIBERNATE_DDL_AUTO", "validate",
                "AUTH_JPA_JDBC_TIME_ZONE", "UTC"
        ));

        assertEquals(Map.of(
                "spring.datasource.url", "jdbc:postgresql://database/auth",
                "spring.datasource.username", "user",
                "spring.datasource.password", "password",
                "server.port", "9091",
                "logging.file.name", "/tmp/auth.log",
                "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC"
        ), AuthServerEnvs.springProperties(env));
    }
}

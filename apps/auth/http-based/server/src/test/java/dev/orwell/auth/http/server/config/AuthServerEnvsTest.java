package dev.orwell.auth.http.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.orwell.env.Env;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvValidationException;
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
        assertThrows(EnvValidationException.class, () -> AuthServerEnvs.ENV.schema().from(Map.of()));
    }

    @Test
    void launcherResolvesDotenvFromAncestralDirectory() throws IOException {
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/child"));
        Files.writeString(
                tempDir.resolve(".env"),
                "AUTH_DATASOURCE_URL=jdbc:postgresql://example:5432/auth\n" +
                        "AUTH_DATASOURCE_USERNAME=example-user\n" +
                        "AUTH_DATASOURCE_PASSWORD=example-pass\n" +
                        "SERVER_ADDRESS=0.0.0.0\n" +
                        "SERVER_PORT=9090\n" +
                        "LOGGING_FILE_NAME=/tmp/auth.log\n" +
                        "AUTH_JPA_HIBERNATE_DDL_AUTO=validate\n" +
                        "AUTH_JPA_JDBC_TIME_ZONE=UTC\n"
        );

        Env env = AuthServerEnvs.ENV.schema().from(EnvFiles.load(nestedDirectory));

        assertEquals("jdbc:postgresql://example:5432/auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_URL));
        assertEquals("example-user", env.get(AuthServerEnvs.AUTH_DATASOURCE_USERNAME));
        assertEquals("example-pass", env.get(AuthServerEnvs.AUTH_DATASOURCE_PASSWORD));
        assertEquals("0.0.0.0", env.get(AuthServerEnvs.ENV.SERVER_ADDRESS));
        assertEquals(9090, env.get(AuthServerEnvs.ENV.SERVER_PORT));
        assertEquals("/tmp/auth.log", env.get(AuthServerEnvs.ENV.LOGGING_FILE_NAME));
    }

    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = AuthServerEnvs.ENV.schema().from(Map.of(
                "AUTH_DATASOURCE_URL", "jdbc:postgresql://database/auth",
                "AUTH_DATASOURCE_USERNAME", "user",
                "AUTH_DATASOURCE_PASSWORD", "password",
                "SERVER_ADDRESS", "0.0.0.0",
                "SERVER_PORT", "9091",
                "LOGGING_FILE_NAME", "/tmp/auth.log",
                "AUTH_JPA_HIBERNATE_DDL_AUTO", "validate",
                "AUTH_JPA_JDBC_TIME_ZONE", "UTC",
                "CLIPPY_AUTH_ROUTE_PREFIX", "/auth"
        ));

        assertEquals(Map.of(
                "spring.datasource.url", "jdbc:postgresql://database/auth",
                "spring.datasource.username", "user",
                "spring.datasource.password", "password",
                "server.address", "0.0.0.0",
                "server.port", 9091,
                "logging.file.name", "/tmp/auth.log",
                "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC",
                "clippy.auth.route-prefix", "/auth"
        ), AuthServerEnvs.ENV.springProperties(env));
    }
}

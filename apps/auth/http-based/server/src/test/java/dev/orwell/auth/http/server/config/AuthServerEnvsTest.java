package dev.orwell.auth.http.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        "AUTH_JPA_HIBERNATE_DDL_AUTO=validate\n" +
                        "AUTH_JPA_JDBC_TIME_ZONE=UTC\n"
        );

        Env env = AuthServerEnvs.ENV.schema().from(EnvFiles.load(nestedDirectory));

        assertEquals("jdbc:postgresql://example:5432/auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_URL));
        assertEquals("example-user", env.get(AuthServerEnvs.AUTH_DATASOURCE_USERNAME));
        assertEquals("example-pass", env.get(AuthServerEnvs.AUTH_DATASOURCE_PASSWORD));
        assertEquals("0.0.0.0", env.get(AuthServerEnvs.ENV.SERVER_ADDRESS));
        assertEquals(9090, env.get(AuthServerEnvs.ENV.SERVER_PORT));
    }

    @Test
    void startsWithoutALogFileBecauseTheServerLogsToConsoleAndLoki() {
        Env env = AuthServerEnvs.ENV.schema().from(Map.of(
                "AUTH_DATASOURCE_URL", "jdbc:postgresql://database/auth",
                "AUTH_DATASOURCE_USERNAME", "user",
                "AUTH_DATASOURCE_PASSWORD", "password",
                "SERVER_ADDRESS", "0.0.0.0",
                "SERVER_PORT", "9091",
                "AUTH_JPA_HIBERNATE_DDL_AUTO", "validate",
                "AUTH_JPA_JDBC_TIME_ZONE", "UTC"
        ));

        assertFalse(AuthServerEnvs.ENV.springProperties(env).containsKey("logging.file.name"));
    }

    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = AuthServerEnvs.ENV.schema().from(Map.ofEntries(
                Map.entry("AUTH_DATASOURCE_URL", "jdbc:postgresql://database/auth"),
                Map.entry("AUTH_DATASOURCE_USERNAME", "user"),
                Map.entry("AUTH_DATASOURCE_PASSWORD", "password"),
                Map.entry("SERVER_ADDRESS", "0.0.0.0"),
                Map.entry("SERVER_PORT", "9091"),
                Map.entry("AUTH_JPA_HIBERNATE_DDL_AUTO", "validate"),
                Map.entry("AUTH_JPA_JDBC_TIME_ZONE", "UTC"),
                Map.entry("AUTH_ROUTE_PREFIX", "/auth"),
                Map.entry("AUTH_ADMIN_BOOTSTRAP_CLIENT_ID", "ops-admin"),
                Map.entry("AUTH_ADMIN_BOOTSTRAP_SECRET", "super-secret"),
                Map.entry("AUTH_TOKEN_CLEANUP_ENABLED", "false"),
                Map.entry("AUTH_TOKEN_CLEANUP_INTERVAL_SECONDS", "900"),
                Map.entry("AUTH_TOKEN_CLEANUP_IDLE_SECONDS", "7200")
        ));

        assertEquals(Map.ofEntries(
                Map.entry("spring.datasource.url", "jdbc:postgresql://database/auth"),
                Map.entry("spring.datasource.username", "user"),
                Map.entry("spring.datasource.password", "password"),
                Map.entry("server.address", "0.0.0.0"),
                Map.entry("server.port", 9091),
                Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                Map.entry("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC"),
                Map.entry("orwell.auth.route-prefix", "/auth"),
                Map.entry("orwell.auth.admin-bootstrap.client-id", "ops-admin"),
                Map.entry("orwell.auth.admin-bootstrap.secret", "super-secret"),
                Map.entry("orwell.auth.token-cleanup.enabled", false),
                Map.entry("orwell.auth.token-cleanup.interval-seconds", 900),
                Map.entry("orwell.auth.token-cleanup.idle-seconds", 7200)
        ), AuthServerEnvs.ENV.springProperties(env));
    }

    /**
     * The cleanup knobs carry their defaults through to Spring even when nobody sets them, which is
     * what makes the job run on a server whose configuration predates it — the one that has been
     * accumulating token rows since it was deployed.
     */
    @Test
    void publishesTokenCleanupDefaultsWhenNothingConfiguresThem() {
        Env env = AuthServerEnvs.ENV.schema().from(Map.of(
                "AUTH_DATASOURCE_URL", "jdbc:postgresql://database/auth",
                "AUTH_DATASOURCE_USERNAME", "user",
                "AUTH_DATASOURCE_PASSWORD", "password",
                "SERVER_ADDRESS", "0.0.0.0",
                "SERVER_PORT", "9091",
                "AUTH_JPA_HIBERNATE_DDL_AUTO", "validate",
                "AUTH_JPA_JDBC_TIME_ZONE", "UTC"
        ));

        Map<String, Object> properties = AuthServerEnvs.ENV.springProperties(env);

        assertEquals(true, properties.get("orwell.auth.token-cleanup.enabled"));
        assertEquals(3600, properties.get("orwell.auth.token-cleanup.interval-seconds"));
        assertEquals(86400, properties.get("orwell.auth.token-cleanup.idle-seconds"));
    }
}

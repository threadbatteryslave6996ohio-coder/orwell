package dev.orwell.secrets;

import dev.orwell.env.Env;
import dev.orwell.env.EnvValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretsManagerEnvsTest {
    @Test
    void requiresAuthBaseUrl() {
        assertThrows(EnvValidationException.class, () -> SecretsManagerEnvs.ENV.schema().from(Map.of(
                "SERVER_ADDRESS", "0.0.0.0",
                "SERVER_PORT", "8083",
                "LOGGING_FILE_NAME", "/tmp/secrets.log",
                "SECRETS_DATASOURCE_URL", "jdbc:postgresql://localhost/secrets",
                "SECRETS_DATASOURCE_USERNAME", "secrets",
                "SECRETS_DATASOURCE_PASSWORD", "secrets",
                "SECRETS_JPA_HIBERNATE_DDL_AUTO", "update",
                "SECRETS_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE", "UTC"
        )));
    }

    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = SecretsManagerEnvs.ENV.schema().from(Map.ofEntries(
                Map.entry("SERVER_ADDRESS", "0.0.0.0"),
                Map.entry("SERVER_PORT", "8085"),
                Map.entry("LOGGING_FILE_NAME", "/tmp/secrets.log"),
                Map.entry("AUTH_BASE_URL", "http://auth-server/auth"),
                Map.entry("SECRETS_DATASOURCE_URL", "jdbc:postgresql://db-secrets/secrets"),
                Map.entry("SECRETS_DATASOURCE_USERNAME", "secrets"),
                Map.entry("SECRETS_DATASOURCE_PASSWORD", "secrets"),
                Map.entry("SECRETS_JPA_HIBERNATE_DDL_AUTO", "update"),
                Map.entry("SECRETS_JPA_JDBC_TIME_ZONE", "UTC"),
                Map.entry("SECRETS_ROUTE_PREFIX", "/secrets")
        ));

        assertEquals(Map.ofEntries(
                Map.entry("server.address", "0.0.0.0"),
                Map.entry("server.port", 8085),
                Map.entry("logging.file.name", "/tmp/secrets.log"),
                Map.entry("orwell.auth.base-url", "http://auth-server/auth"),
                Map.entry("spring.datasource.url", "jdbc:postgresql://db-secrets/secrets"),
                Map.entry("spring.datasource.username", "secrets"),
                Map.entry("spring.datasource.password", "secrets"),
                Map.entry("spring.jpa.hibernate.ddl-auto", "update"),
                Map.entry("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC"),
                Map.entry("secrets.route-prefix", "/secrets")
        ), SecretsManagerEnvs.ENV.springProperties(env));
    }

    @Test
    void servesRoutesUnprefixedWhenNoRoutePrefixIsSupplied() {
        Env env = SecretsManagerEnvs.ENV.schema().from(Map.of(
                "SERVER_ADDRESS", "0.0.0.0",
                "SERVER_PORT", "8085",
                "LOGGING_FILE_NAME", "/tmp/secrets.log",
                "AUTH_BASE_URL", "http://auth-server/auth",
                "SECRETS_DATASOURCE_URL", "jdbc:postgresql://db-secrets/secrets",
                "SECRETS_DATASOURCE_USERNAME", "secrets",
                "SECRETS_DATASOURCE_PASSWORD", "secrets",
                "SECRETS_JPA_HIBERNATE_DDL_AUTO", "update",
                "SECRETS_JPA_JDBC_TIME_ZONE", "UTC"
        ));

        assertEquals("", env.get(SecretsManagerEnvs.SECRETS_ROUTE_PREFIX));
    }
}

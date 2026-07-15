package dev.orwell.secrets;

import dev.orwell.env.EnvValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}

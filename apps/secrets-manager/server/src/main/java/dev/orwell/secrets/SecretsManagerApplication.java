package dev.orwell.secrets;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import dev.orwell.logging.CustomLogger;
import dev.orwell.logging.Logger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@SpringBootApplication
public class SecretsManagerApplication {
    public static ConfigurableApplicationContext start(Env env) {
        return SpringServerBootstrap.start(
                SecretsManagerApplication.class,
                env.get(SecretsManagerEnvs.SECRETS_LOGGING_FILE_NAME),
                SecretsManagerEnvs.springProperties(env),
                "secretsManagerLauncher");
    }

    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        return start(SecretsManagerEnvs.from(environment));
    }

    /** Custom {@link Logger} available for injection across the secrets manager. */
    @Bean
    public Logger logger() {
        return new CustomLogger("secrets-manager");
    }
}

package dev.orwell.secrets;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

@SpringBootApplication
public class SecretsManagerApplication {
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        Env env = SecretsManagerEnvs.from(environment);
        return SpringServerBootstrap.start(
                SecretsManagerApplication.class,
                env.get(SecretsManagerEnvs.SECRETS_LOGGING_FILE_NAME),
                SecretsManagerEnvs.springProperties(env),
                "secretsManagerLauncher");
    }
}

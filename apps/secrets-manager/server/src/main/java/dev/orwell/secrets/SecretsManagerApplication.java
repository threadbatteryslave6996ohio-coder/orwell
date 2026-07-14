package dev.orwell.secrets;

import dev.orwell.bootstrap.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SecretsManagerApplication {
    /**
     * Server descriptor: how the environment is fetched stays with whoever calls
     * {@code SERVER.start(...)} / {@code runOrExit}; the core never reads {@code .env} files itself.
     */
    public static final AppServer SERVER = AppServer.spring(SecretsManagerApplication.class)
            .name("secrets-manager")
            .envs(SecretsManagerEnvs.ENV)
            .properties(SecretsManagerEnvs::springProperties)
            .loggingFile(env -> env.get(SecretsManagerEnvs.SECRETS_LOGGING_FILE_NAME))
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}

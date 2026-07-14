package dev.orwell.server.application;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.server.config.ServerEnvs;
import dev.orwell.server.repository.ClipboardEntryRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "dev.orwell.server")
@EnableJpaRepositories(basePackageClasses = ClipboardEntryRepository.class)
@EntityScan(basePackages = "dev.orwell.server.model")
public class ClippyServerApplication {
    /**
     * Server descriptor: how the environment is fetched stays with whoever calls
     * {@code SERVER.start(...)} / {@code runOrExit}; the core never reads {@code .env} files itself.
     */
    public static final AppServer SERVER = AppServer.spring(ClippyServerApplication.class)
            .name("clippy-server")
            .envs(ServerEnvs.ENV)
            .properties(ServerEnvs::springProperties)
            .loggingFile(env -> env.get(ServerEnvs.LOGGING_FILE_NAME))
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}

package dev.orwell.combined;

import dev.orwell.bootstrap.SpringServerBootstrap;
import dev.orwell.env.Env;
import dev.orwell.logging.CustomLogger;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Map;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class
})
@Import({
        CombinedAuthModuleConfiguration.class,
        CombinedAuthDatabaseConfiguration.class,
        CombinedClipboardModuleConfiguration.class,
        CombinedClipboardDatabaseConfiguration.class,
        CombinedJarvisModuleConfiguration.class,
        CombinedKeeboarderModuleConfiguration.class,
        CombinedSecretsModuleConfiguration.class,
        CombinedSecretsDatabaseConfiguration.class
})
public class CombinedServerApplication {
    public static ConfigurableApplicationContext start(Env env) {
        return SpringServerBootstrap.start(
                CombinedServerApplication.class,
                env.get(CombinedEnvs.LOGGING_FILE_NAME),
                CombinedServerApplication::logCombinedModeDisclaimer,
                CombinedEnvs.springProperties(env),
                "combinedServerLauncher");
    }

    /** Starts the core from configuration that has already been fetched by a launcher. */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        return start(CombinedEnvs.from(environment));
    }

    static void logCombinedModeDisclaimer() {
        try {
            new CustomLogger("combined-server").log(
                    "Combined mode is active: auth, Klippy, Jarvis, Keeboarder, and Secrets routes run as sub-apps in one JVM."
            );
        } catch (IllegalStateException exception) {
            System.err.println("Combined server diagnostic log could not be written: " + exception.getMessage());
        }
    }
}

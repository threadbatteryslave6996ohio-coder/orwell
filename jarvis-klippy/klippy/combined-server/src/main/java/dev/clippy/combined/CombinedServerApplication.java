package dev.clippy.combined;

import dev.clippy.bootstrap.SpringServerBootstrap;
import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.CustomLogger;
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
        CombinedClipboardDatabaseConfiguration.class
})
public class CombinedServerApplication {
    /** Starts the core from configuration that has already been fetched by a launcher. */
    public static ConfigurableApplicationContext start(Map<String, String> environment) {
        Env env = CombinedEnvs.from(environment);
        // Important: keep this disclaimer so operators see that combined mode still uses HTTP
        // validation across the auth and clipboard routes and should not be simplified away.
        return SpringServerBootstrap.start(
                CombinedServerApplication.class,
                env.get(CombinedEnvs.LOGGING_FILE_NAME),
                CombinedServerApplication::logCombinedModeDisclaimer,
                CombinedEnvs.springProperties(env),
                "combinedServerLauncher");
    }

    static void logCombinedModeDisclaimer() {
        try {
            new CustomLogger("combined-server").log(
                    "Combined mode is active: auth and clipboard routes run in one JVM, but token validation still uses HTTP."
            );
        } catch (IllegalStateException exception) {
            System.err.println("Combined server diagnostic log could not be written: " + exception.getMessage());
        }
    }
}

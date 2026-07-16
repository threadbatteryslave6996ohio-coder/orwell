package dev.orwell.bootstrap.launch;

import dev.orwell.env.EnvValidationException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootApplication
class AppServerTest {
    @Test
    void loadsArgsValidatesAgainstSchemaAndStartsApplication() throws IOException {
        AppServerEnv environment = new AppServerEnv(false, false);
        AppServer application = new AppServer(AppServerTest.class, "app-server-test", environment);

        ConfigurableApplicationContext started = application.start(
                new String[]{"remote", "http://localhost:9999/env"},
                args -> {
                    assertEquals("remote", args[0]);
                    assertEquals("http://localhost:9999/env", args[1]);
                    return Map.of("SERVER_ADDRESS", "127.0.0.1", "SERVER_PORT", "9090");
                });

        assertEquals("9090", started.getEnvironment().getProperty("server.port"));
        assertEquals("app-server-test", started.getEnvironment().getProperty("orwell.app.name"));
        started.close();
    }

    @Test
    void rejectsMissingRequiredEnvironmentValues() {
        AppServerEnv environment = new AppServerEnv(false, false);

        assertThrows(
                EnvValidationException.class,
                () -> new AppServer(AppServerTest.class, "app-server-test", environment)
                        .start(new String[0], args -> Map.of())
        );
    }

    @Test
    void rejectsMissingServerAddress() {
        AppServerEnv environment = new AppServerEnv(false, false);

        assertThrows(EnvValidationException.class, () ->
                new AppServer(AppServerTest.class, "app-server-test", environment)
                        .start(Map.of("SERVER_PORT", "9090")));
    }

    @Test
    void rejectsMissingServerPort() {
        AppServerEnv environment = new AppServerEnv(false, false);

        assertThrows(EnvValidationException.class, () ->
                new AppServer(AppServerTest.class, "app-server-test", environment)
                        .start(Map.of("SERVER_ADDRESS", "127.0.0.1")));
    }

    @Test
    void startupHookRunsAfterLoggingDirectoryConfiguration() {
        AppServerEnv environment = new AppServerEnv(true, false);
        AtomicBoolean hookRanAfterLoggingConfiguration = new AtomicBoolean();
        AppServer application = new AppServer(
                AppServerTest.class,
                "app-server-test",
                environment,
                env -> hookRanAfterLoggingConfiguration.set(
                        "/tmp".equals(System.getProperty("custom.logger.dir")))
        );

        ConfigurableApplicationContext started = application.start(Map.of(
                "SERVER_ADDRESS", "127.0.0.1",
                "SERVER_PORT", "9090",
                "LOGGING_FILE_NAME", "/tmp/app-server-test.log"
        ));

        assertEquals(true, hookRanAfterLoggingConfiguration.get());
        started.close();
    }

    @Test
    void standardLoaderRejectsUnknownLoaderName() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> SpringEnvLoaders.standard().load(new String[]{"bogus"})
        );

        assertEquals(
                "Unknown env loader: bogus (expected 'file', 'url', or 'remote')",
                error.getMessage()
        );
    }

}

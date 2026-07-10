package dev.orwell.bootstrap;

import dev.orwell.env.Env;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;
import dev.orwell.env.EnvValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppServerTest {
    @Test
    void loadsArgsValidatesAgainstSchemaAndStartsApplication() throws IOException {
        var builder = EnvSchema.builder();
        var port = builder.required("SERVER_PORT", EnvType.string());
        EnvSchema schema = builder.build();
        AtomicReference<String> receivedPort = new AtomicReference<>();
        ConfigurableApplicationContext context = new GenericApplicationContext();

        AppServer application = new AppServer(
                schema,
                env -> captureAndReturn(env, port, receivedPort, context)
        );

        ConfigurableApplicationContext started = application.start(
                new String[]{"remote", "http://localhost:9999/env"},
                args -> {
                    assertEquals("remote", args[0]);
                    assertEquals("http://localhost:9999/env", args[1]);
                    return Map.of("SERVER_PORT", "9090");
                });

        assertSame(context, started);
        assertEquals("9090", receivedPort.get());
    }

    @Test
    void rejectsMissingRequiredEnvironmentValues() {
        var builder = EnvSchema.builder();
        builder.required("SERVER_PORT", EnvType.string());
        EnvSchema schema = builder.build();

        assertThrows(
                EnvValidationException.class,
                () -> new AppServer(schema, env -> new GenericApplicationContext())
                        .start(new String[0], args -> Map.of())
        );
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

    private static ConfigurableApplicationContext captureAndReturn(
            Env env,
            dev.orwell.env.EnvOption<String> port,
            AtomicReference<String> receivedPort,
            ConfigurableApplicationContext context
    ) {
        receivedPort.set(env.get(port));
        return context;
    }
}

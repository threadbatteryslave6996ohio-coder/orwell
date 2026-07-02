package dev.clippy.utils.envmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvSchemaTest {
    @Test
    void parsesTypedValuesFromSchema() {
        EnvClassBuilder builder = EnvSchema.builder();
        EnvOption<String> url = builder.required("REMOTE_SERVER_URL", EnvType.string());
        EnvOption<Integer> interval = builder.optional("CLIPBOARD_POLL_INTERVAL_MS", EnvType.integer(), 1000);
        EnvOption<Boolean> debug = builder.optional("DEBUG", EnvType.bool(), false);

        Env env = builder.build().from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "DEBUG", "yes"
        ));

        assertEquals("http://localhost:8080", env.get(url));
        assertEquals(1000, env.get(interval));
        assertTrue(env.get(debug));
    }

    @Test
    void rejectsMissingRequiredValues() {
        EnvClassBuilder builder = EnvSchema.builder();
        builder.required("REMOTE_SERVER_URL", EnvType.string());

        EnvSchema schema = builder.build();

        assertThrows(EnvValidationException.class, () -> schema.from(Map.of()));
    }

    @Test
    void rejectsInvalidTypedValues() {
        EnvClassBuilder builder = EnvSchema.builder();
        builder.required("SERVER_PORT", EnvType.integer());

        EnvSchema schema = builder.build();

        assertThrows(EnvValidationException.class, () -> schema.from(Map.of("SERVER_PORT", "not-a-port")));
    }

    @Test
    void tracksWhetherOptionalValuesWereProvided() {
        EnvClassBuilder builder = EnvSchema.builder();
        EnvOption<String> clientId = builder.optional("CLIENT_ID", EnvType.string());

        Env env = builder.build().from(Map.of());

        assertFalse(env.has(clientId));
    }
}

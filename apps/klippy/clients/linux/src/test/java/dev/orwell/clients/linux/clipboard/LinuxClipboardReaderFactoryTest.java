package dev.orwell.clients.linux.clipboard;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxClipboardReaderFactoryTest {
    @Test
    void rejectsUnsupportedRequestedBackend() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIPBOARD_BACKEND", "not-a-backend"
        ));

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> LinuxClipboardReaderFactory.create(env));
        assertTrue(exception.getMessage().contains("not-a-backend"),
                () -> "message should name the unsupported backend but was: " + exception.getMessage());
    }
}

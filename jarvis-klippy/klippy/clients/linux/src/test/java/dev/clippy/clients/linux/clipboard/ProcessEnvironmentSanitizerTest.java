package dev.clippy.clients.linux.clipboard;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessEnvironmentSanitizerTest {
    @Test
    void stripsDesktopLaunchMetadataFromClipboardHelperEnvironment() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "DESKTOP_STARTUP_ID", "startup-id",
                "XDG_ACTIVATION_TOKEN", "activation-token",
                "GIO_LAUNCHED_DESKTOP_FILE", "/tmp/clippy.desktop",
                "GIO_LAUNCHED_DESKTOP_FILE_PID", "123",
                "BAMF_DESKTOP_FILE_HINT", "/tmp/clippy.desktop"
        ));

        ProcessEnvironmentSanitizer.stripDesktopLaunchEnvironment(environment);

        assertEquals(Map.of("PATH", "/usr/bin"), environment);
    }
}

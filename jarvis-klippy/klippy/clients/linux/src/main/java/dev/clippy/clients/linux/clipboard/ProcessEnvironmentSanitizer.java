package dev.clippy.clients.linux.clipboard;

import java.util.List;
import java.util.Map;

/**
 * Removes desktop-launch metadata from a child process environment. Poll helpers are
 * not desktop applications and must not inherit the launch identity of the terminal or
 * IDE that started Clippy.
 */
public final class ProcessEnvironmentSanitizer {
    private static final List<String> DESKTOP_LAUNCH_ENVIRONMENT = List.of(
            "DESKTOP_STARTUP_ID",
            "XDG_ACTIVATION_TOKEN",
            "GIO_LAUNCHED_DESKTOP_FILE",
            "GIO_LAUNCHED_DESKTOP_FILE_PID",
            "BAMF_DESKTOP_FILE_HINT"
    );

    private ProcessEnvironmentSanitizer() {
    }

    public static void stripDesktopLaunchEnvironment(Map<String, String> environment) {
        DESKTOP_LAUNCH_ENVIRONMENT.forEach(environment::remove);
    }
}

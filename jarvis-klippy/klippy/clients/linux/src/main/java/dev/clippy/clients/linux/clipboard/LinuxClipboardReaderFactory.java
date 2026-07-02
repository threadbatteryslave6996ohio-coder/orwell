package dev.clippy.clients.linux.clipboard;

import dev.clippy.clients.core.env.ClientEnvs;
import dev.clippy.utils.envmanager.Env;

import java.util.ArrayList;
import java.util.List;

/**
 * Selects the Linux clipboard backend. When {@code CLIPBOARD_BACKEND} is set the request
 * is honored (or fails loudly); otherwise the first available backend is chosen, preferring
 * the long-lived AWT connection over per-poll command processes.
 */
public final class LinuxClipboardReaderFactory {
    private LinuxClipboardReaderFactory() {
    }

    public static LinuxClipboardReader create(Env env) {
        if (env.has(ClientEnvs.CLIPBOARD_BACKEND)) {
            String requestedBackend = env.get(ClientEnvs.CLIPBOARD_BACKEND);
            LinuxClipboardReader reader = switch (requestedBackend.trim().toLowerCase()) {
                case "wl-paste", "wayland" -> new CommandClipboardReader("wl-paste", List.of("wl-paste", "--no-newline", "--type", "text/plain"));
                case "xclip" -> new CommandClipboardReader("xclip", List.of("xclip", "-selection", "clipboard", "-out", "-target", "UTF8_STRING"));
                case "xsel" -> new CommandClipboardReader("xsel", List.of("xsel", "--clipboard", "--output"));
                case "awt", "java" -> new AwtClipboardReader();
                default -> throw new IllegalArgumentException("Unsupported " + ClientEnvs.CLIPBOARD_BACKEND.name() + ": " + requestedBackend);
            };
            if (!reader.isAvailable()) {
                throw new IllegalStateException("Requested clipboard backend is not available: " + requestedBackend);
            }
            return reader;
        }

        List<LinuxClipboardReader> candidates = new ArrayList<>();
        boolean wayland = envPresent("WAYLAND_DISPLAY");
        boolean x11 = envPresent("DISPLAY");

        // AWT keeps one clipboard connection open. Prefer it over command
        // backends, which must create a process for every poll.
        candidates.add(new AwtClipboardReader());
        if (wayland && Executables.exists("wl-paste")) {
            candidates.add(new CommandClipboardReader("wl-paste", List.of("wl-paste", "--no-newline", "--type", "text/plain")));
        }
        if (x11 && Executables.exists("xclip")) {
            candidates.add(new CommandClipboardReader("xclip", List.of("xclip", "-selection", "clipboard", "-out", "-target", "UTF8_STRING")));
        }
        if (x11 && Executables.exists("xsel")) {
            candidates.add(new CommandClipboardReader("xsel", List.of("xsel", "--clipboard", "--output")));
        }

        return candidates.stream()
                .filter(LinuxClipboardReader::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No clipboard backend is available. Install wl-clipboard on GNOME Wayland, or xclip/xsel on X11."));
    }

    private static boolean envPresent(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}

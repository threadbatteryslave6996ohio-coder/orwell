package dev.orwell.keeboarder.linux;

import java.util.List;

final class KeyboardMonitors {
    private KeyboardMonitors() {
    }

    static KeyboardMonitor create(KeyEventListener listener, FailureListener failureListener) {
        String backend = System.getenv().getOrDefault("KEEBOARDER_KEYBOARD_BACKEND", "auto");
        if ("evdev".equalsIgnoreCase(backend)
                || ("auto".equalsIgnoreCase(backend)
                && "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")))) {
            return new EvdevKeyboardMonitor(listener, failureListener);
        }
        if (!"auto".equalsIgnoreCase(backend) && !"x11".equalsIgnoreCase(backend)) {
            throw new IllegalArgumentException("Unknown KEEBOARDER_KEYBOARD_BACKEND: " + backend
                    + " (expected auto, x11, or evdev)");
        }
        return new X11KeyboardMonitor(listener, failureListener);
    }

    interface KeyEventListener {
        void onKeyEvent(String eventName, int keyCode, String keyName, List<String> modifiers);
    }

    interface FailureListener {
        void onFailure(RuntimeException exception);
    }
}

package dev.orwell.keeboarder.linux;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.PointerType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

final class X11KeyboardMonitor {
    private static final long POLL_INTERVAL_MS = 25L;
    private static final long REPEAT_DELAY_MS = 400L;
    private static final long REPEAT_INTERVAL_MS = 35L;
    private static final Set<String> NON_REPEATABLE_KEYS = Set.of("shift", "control", "alt", "super", "caps_lock");

    private final KeyEventListener listener;
    private final FailureListener failureListener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Integer, Long> nextRepeatAtMillis = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Thread worker;

    X11KeyboardMonitor(KeyEventListener listener, FailureListener failureListener) {
        this.listener = listener;
        this.failureListener = failureListener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::runLoop, "keeboarder-linux-x11");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running.set(false);
        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        try (DisplayHandle display = openDisplay()) {
            byte[] previous = queryKeymap(display.display());
            while (running.get()) {
                byte[] current = queryKeymap(display.display());
                emitEvents(previous, current, code -> keyName(display.display(), code), System.currentTimeMillis());
                System.arraycopy(current, 0, previous, 0, X11KeyStates.KEYMAP_BYTES);
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException runtimeException) {
            failureListener.onFailure(runtimeException);
        }
    }

    void emitEvents(byte[] previous, byte[] current, IntFunction<String> keyNameResolver, long nowMillis) {
        List<Integer> changedKeyCodes = X11KeyStates.changedKeyCodes(previous, current);
        for (int keyCode : changedKeyCodes) {
            boolean pressed = X11KeyStates.isPressed(current, keyCode);
            String keyName = keyNameResolver.apply(keyCode);
            List<String> modifiers = X11KeyStates.activeModifiers(current, keyNameResolver);
            if (pressed && isRepeatable(keyName)) {
                nextRepeatAtMillis.put(keyCode, nowMillis + REPEAT_DELAY_MS);
            } else {
                nextRepeatAtMillis.remove(keyCode);
            }
            listener.onKeyEvent(pressed ? "press" : "release", keyCode, keyName, modifiers);
        }

        for (Map.Entry<Integer, Long> entry : List.copyOf(nextRepeatAtMillis.entrySet())) {
            int keyCode = entry.getKey();
            if (changedKeyCodes.contains(keyCode)) {
                continue;
            }
            if (!X11KeyStates.isPressed(current, keyCode)) {
                nextRepeatAtMillis.remove(keyCode);
                continue;
            }
            long nextRepeatAt = entry.getValue();
            if (nowMillis < nextRepeatAt) {
                continue;
            }
            String keyName = keyNameResolver.apply(keyCode);
            if (!isRepeatable(keyName)) {
                nextRepeatAtMillis.remove(keyCode);
                continue;
            }
            List<String> modifiers = X11KeyStates.activeModifiers(current, keyNameResolver);
            listener.onKeyEvent("press", keyCode, keyName, modifiers);
            nextRepeatAtMillis.put(keyCode, advanceRepeatDeadline(nextRepeatAt, nowMillis));
        }
    }

    private DisplayHandle openDisplay() {
        String sessionType = System.getenv("XDG_SESSION_TYPE");
        if ("wayland".equalsIgnoreCase(sessionType)) {
            throw new IllegalStateException("Wayland sessions are not supported. Start Keeboarder from an X11 session.");
        }
        String displayName = System.getenv("DISPLAY");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalStateException("DISPLAY is not set. Start Keeboarder from an X11 desktop session.");
        }
        X11Library.Display display = X11Library.INSTANCE.XOpenDisplay(null);
        if (display == null) {
            throw new IllegalStateException("Cannot open X11 display " + displayName + ".");
        }
        return new DisplayHandle(display);
    }

    private static byte[] queryKeymap(X11Library.Display display) {
        byte[] keys = new byte[X11KeyStates.KEYMAP_BYTES];
        X11Library.INSTANCE.XQueryKeymap(display, keys);
        return keys;
    }

    private static long advanceRepeatDeadline(long nextRepeatAt, long nowMillis) {
        long deadline = nextRepeatAt + REPEAT_INTERVAL_MS;
        while (deadline <= nowMillis) {
            deadline += REPEAT_INTERVAL_MS;
        }
        return deadline;
    }

    private static boolean isRepeatable(String keyName) {
        return !NON_REPEATABLE_KEYS.contains(keyName);
    }

    private String keyName(X11Library.Display display, int keyCode) {
        NativeLong keysym = X11Library.INSTANCE.XkbKeycodeToKeysym(display, keyCode, 0, 0);
        String rawName = keysym.longValue() == 0 ? null : X11Library.INSTANCE.XKeysymToString(keysym);
        return X11KeyNames.normalize(rawName, keyCode);
    }

    @FunctionalInterface
    interface KeyEventListener {
        void onKeyEvent(String eventName, int keyCode, String keyName, List<String> modifiers);
    }

    @FunctionalInterface
    interface FailureListener {
        void onFailure(RuntimeException exception);
    }

    private record DisplayHandle(X11Library.Display display) implements AutoCloseable {
        @Override
        public void close() {
            X11Library.INSTANCE.XCloseDisplay(display);
        }
    }

    interface X11Library extends Library {
        X11Library INSTANCE = Native.load("X11", X11Library.class);

        final class Display extends PointerType {
        }

        Display XOpenDisplay(String displayName);

        int XCloseDisplay(Display display);

        int XQueryKeymap(Display display, byte[] keysReturn);

        NativeLong XkbKeycodeToKeysym(Display display, int keyCode, int group, int level);

        String XKeysymToString(NativeLong keysym);
    }
}

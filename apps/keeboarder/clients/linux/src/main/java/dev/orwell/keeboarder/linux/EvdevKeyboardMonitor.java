package dev.orwell.keeboarder.linux;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads Linux input events directly, which works from Wayland without compositor hooks. */
final class EvdevKeyboardMonitor implements KeyboardMonitor {
    private static final int O_RDONLY = 0;
    private static final int O_NONBLOCK = 0x800;
    private static final short EV_KEY = 1;
    private static final int EVENT_SIZE = 24; // 64-bit Linux: timeval (16) + type/code/value (8)
    private static final int EVENT_TYPE_OFFSET = 16;
    private static final int EVENT_CODE_OFFSET = 18;
    private static final int EVENT_VALUE_OFFSET = 20;
    private static final Set<Integer> MODIFIER_CODES = Set.of(29, 42, 54, 56, 58, 97, 100, 125, 126);

    private final KeyboardMonitors.KeyEventListener listener;
    private final KeyboardMonitors.FailureListener failureListener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile List<Integer> fileDescriptors = List.of();

    EvdevKeyboardMonitor(KeyboardMonitors.KeyEventListener listener,
                         KeyboardMonitors.FailureListener failureListener) {
        this.listener = listener;
        this.failureListener = failureListener;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::runLoop, "keeboarder-linux-evdev");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        closeDevices();
    }

    private void runLoop() {
        try {
            openDevices();
            if (fileDescriptors.isEmpty()) {
                throw new IllegalStateException("No readable /dev/input/event* devices found. "
                        + "Grant this user read access to the input devices (usually the input group).");
            }
            Memory event = new Memory(EVENT_SIZE);
            while (running.get()) {
                boolean received = false;
                for (int fd : fileDescriptors) {
                    int bytes = LibC.INSTANCE.read(fd, event, EVENT_SIZE);
                    if (bytes != EVENT_SIZE) {
                        continue;
                    }
                    received = true;
                    emit(event);
                }
                if (!received) {
                    Thread.sleep(10L);
                }
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException runtimeException) {
            failureListener.onFailure(runtimeException);
        } finally {
            closeDevices();
        }
    }

    private void openDevices() {
        try (var paths = Files.list(Path.of("/dev/input"))) {
            List<Integer> fds = new ArrayList<>();
            paths.filter(path -> path.getFileName().toString().matches("event\\d+"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        int fd = LibC.INSTANCE.open(path.toString(), O_RDONLY | O_NONBLOCK);
                        if (fd >= 0) {
                            fds.add(fd);
                        }
                    });
            fileDescriptors = List.copyOf(fds);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect /dev/input: " + exception.getMessage(), exception);
        }
    }

    private void closeDevices() {
        for (int fd : fileDescriptors) {
            LibC.INSTANCE.close(fd);
        }
        fileDescriptors = List.of();
    }

    private void emit(Pointer event) {
        ByteBuffer bytes = event.getByteBuffer(0, EVENT_SIZE).order(ByteOrder.nativeOrder());
        if (bytes.getShort(EVENT_TYPE_OFFSET) != EV_KEY) {
            return;
        }
        int keyCode = Short.toUnsignedInt(bytes.getShort(EVENT_CODE_OFFSET));
        int value = bytes.getInt(EVENT_VALUE_OFFSET);
        if (keyCode <= 0 || keyCode > 255 || (value != 0 && value != 1 && value != 2)) {
            return;
        }
        String keyName = LinuxKeyNames.name(keyCode);
        List<String> modifiers = activeModifiers(keyCode, value != 0);
        listener.onKeyEvent(value == 0 ? "release" : "press", keyCode, keyName, modifiers);
    }

    private static List<String> activeModifiers(int currentCode, boolean currentPressed) {
        // The event stream gives us reliable modifier identity without requiring XKB.
        // Include the current modifier for modifier press events; state of other modifiers
        // is reconstructed by the client from the event sequence.
        if (!currentPressed || !MODIFIER_CODES.contains(currentCode)) {
            return List.of();
        }
        return switch (currentCode) {
            case 42, 54 -> List.of("shift");
            case 29, 97 -> List.of("control");
            case 56, 100 -> List.of("alt");
            case 125, 126 -> List.of("super");
            default -> List.of();
        };
    }

    interface LibC extends Library {
        LibC INSTANCE = Native.load("c", LibC.class);

        int open(String path, int flags);

        int close(int fd);

        int read(int fd, Pointer buffer, int count);
    }
}

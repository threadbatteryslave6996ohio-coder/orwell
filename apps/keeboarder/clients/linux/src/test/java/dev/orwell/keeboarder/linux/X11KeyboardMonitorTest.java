package dev.orwell.keeboarder.linux;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class X11KeyboardMonitorTest {
    @Test
    void emitsRepeatPressForHeldNonModifierKey() {
        List<String> events = new ArrayList<>();
        X11KeyboardMonitor monitor = new X11KeyboardMonitor(
                (eventName, keyCode, keyName, modifiers) -> events.add(eventName + ":" + keyName),
                exception -> {
                    throw exception;
                }
        );

        byte[] previous = new byte[X11KeyStates.KEYMAP_BYTES];
        byte[] current = new byte[X11KeyStates.KEYMAP_BYTES];
        press(current, 38);

        monitor.emitEvents(previous, current, keyCode -> keyCode == 38 ? "a" : "keyCode_" + keyCode, 1_000L);
        monitor.emitEvents(current, current, keyCode -> keyCode == 38 ? "a" : "keyCode_" + keyCode, 1_450L);
        monitor.emitEvents(current, current, keyCode -> keyCode == 38 ? "a" : "keyCode_" + keyCode, 1_470L);

        assertEquals(List.of("press:a", "press:a", "press:a"), events);
    }

    @Test
    void doesNotRepeatHeldModifierKey() {
        List<String> events = new ArrayList<>();
        X11KeyboardMonitor monitor = new X11KeyboardMonitor(
                (eventName, keyCode, keyName, modifiers) -> events.add(eventName + ":" + keyName),
                exception -> {
                    throw exception;
                }
        );

        byte[] previous = new byte[X11KeyStates.KEYMAP_BYTES];
        byte[] current = new byte[X11KeyStates.KEYMAP_BYTES];
        press(current, 50);

        monitor.emitEvents(previous, current, keyCode -> keyCode == 50 ? "shift" : "keyCode_" + keyCode, 1_000L);
        monitor.emitEvents(current, current, keyCode -> keyCode == 50 ? "shift" : "keyCode_" + keyCode, 1_450L);

        assertEquals(List.of("press:shift"), events);
    }

    private static void press(byte[] keymap, int keyCode) {
        keymap[keyCode >>> 3] |= (byte) (1 << (keyCode & 7));
    }
}

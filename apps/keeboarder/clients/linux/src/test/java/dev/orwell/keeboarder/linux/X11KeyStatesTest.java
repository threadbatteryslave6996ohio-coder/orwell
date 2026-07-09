package dev.orwell.keeboarder.linux;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class X11KeyStatesTest {
    @Test
    void detectsPressedKeysFromBitset() {
        byte[] keymap = new byte[X11KeyStates.KEYMAP_BYTES];
        press(keymap, 38);
        press(keymap, 50);

        assertTrue(X11KeyStates.isPressed(keymap, 38));
        assertTrue(X11KeyStates.isPressed(keymap, 50));
        assertFalse(X11KeyStates.isPressed(keymap, 39));
    }

    @Test
    void reportsChangedKeyCodesBetweenSnapshots() {
        byte[] previous = new byte[X11KeyStates.KEYMAP_BYTES];
        byte[] current = new byte[X11KeyStates.KEYMAP_BYTES];
        press(previous, 38);
        press(current, 39);

        assertEquals(List.of(38, 39), X11KeyStates.changedKeyCodes(previous, current));
    }

    @Test
    void decodesFirstKeymapBitAsKeycodeEight() {
        byte[] keymap = new byte[X11KeyStates.KEYMAP_BYTES];
        keymap[1] = 1;

        assertTrue(X11KeyStates.isPressed(keymap, 8));
        assertEquals(List.of(8), X11KeyStates.changedKeyCodes(new byte[X11KeyStates.KEYMAP_BYTES], keymap));
    }

    @Test
    void collectsDistinctActiveModifiers() {
        byte[] keymap = new byte[X11KeyStates.KEYMAP_BYTES];
        press(keymap, 50);
        press(keymap, 62);
        press(keymap, 64);
        press(keymap, 37);

        Map<Integer, String> names = Map.of(
                50, "shift",
                62, "shift",
                64, "alt",
                37, "control");

        assertEquals(
                List.of("control", "shift", "alt"),
                X11KeyStates.activeModifiers(keymap, keyCode -> names.getOrDefault(keyCode, "keyCode_" + keyCode)));
    }

    private static void press(byte[] keymap, int keyCode) {
        keymap[keyCode >>> 3] |= (byte) (1 << (keyCode & 7));
    }
}

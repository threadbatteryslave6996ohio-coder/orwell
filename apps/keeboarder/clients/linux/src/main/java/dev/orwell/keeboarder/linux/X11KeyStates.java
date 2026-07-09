package dev.orwell.keeboarder.linux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntFunction;

final class X11KeyStates {
    static final int KEYMAP_BYTES = 32;
    static final int KEYCODE_OFFSET = 8;
    static final int KEYCODE_COUNT = KEYMAP_BYTES * 8;

    private X11KeyStates() {
    }

    static boolean isPressed(byte[] keymap, int keyCode) {
        if (keyCode < 0 || keyCode >= KEYCODE_COUNT) {
            return false;
        }
        int index = keyCode >>> 3;
        int bit = keyCode & 7;
        return ((keymap[index] & 0xFF) & (1 << bit)) != 0;
    }

    static List<Integer> changedKeyCodes(byte[] previous, byte[] current) {
        List<Integer> changed = new ArrayList<>();
        for (int index = 0; index < KEYMAP_BYTES; index++) {
            int delta = ((previous[index] & 0xFF) ^ (current[index] & 0xFF));
            if (delta == 0) {
                continue;
            }
            for (int bit = 0; bit < 8; bit++) {
                if ((delta & (1 << bit)) != 0) {
                    changed.add(index * 8 + bit);
                }
            }
        }
        return changed;
    }

    static List<String> activeModifiers(byte[] keymap, IntFunction<String> keyNameLookup) {
        LinkedHashSet<String> modifiers = new LinkedHashSet<>();
        for (int keyCode = KEYCODE_OFFSET; keyCode < KEYCODE_COUNT; keyCode++) {
            if (!isPressed(keymap, keyCode)) {
                continue;
            }
            switch (keyNameLookup.apply(keyCode)) {
                case "shift" -> modifiers.add("shift");
                case "control" -> modifiers.add("control");
                case "alt" -> modifiers.add("alt");
                case "super" -> modifiers.add("super");
                default -> {
                }
            }
        }
        return List.copyOf(modifiers);
    }
}

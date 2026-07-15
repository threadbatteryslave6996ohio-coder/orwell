package dev.orwell.keeboarder.linux;

import java.util.Map;

final class LinuxKeyNames {
    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(1, "escape"), Map.entry(14, "backspace"), Map.entry(15, "tab"),
            Map.entry(28, "return"), Map.entry(29, "control"), Map.entry(42, "shift"),
            Map.entry(54, "shift"), Map.entry(56, "alt"), Map.entry(57, "space"),
            Map.entry(58, "caps_lock"), Map.entry(97, "control"), Map.entry(100, "alt"),
            Map.entry(125, "super"), Map.entry(126, "super"), Map.entry(103, "up"),
            Map.entry(105, "left"), Map.entry(106, "right"), Map.entry(108, "down"),
            Map.entry(2, "1"), Map.entry(3, "2"), Map.entry(4, "3"), Map.entry(5, "4"),
            Map.entry(6, "5"), Map.entry(7, "6"), Map.entry(8, "7"), Map.entry(9, "8"),
            Map.entry(10, "9"), Map.entry(11, "0"), Map.entry(12, "minus"), Map.entry(13, "equal"),
            Map.entry(26, "bracket_left"), Map.entry(27, "bracket_right"), Map.entry(39, "semicolon"),
            Map.entry(40, "apostrophe"), Map.entry(41, "grave"), Map.entry(43, "backslash"),
            Map.entry(51, "comma"), Map.entry(52, "period"), Map.entry(53, "slash")
    );

    private LinuxKeyNames() {
    }

    static String name(int code) {
        if (code >= 16 && code <= 25) {
            return "qwertyuiop".substring(code - 16, code - 15);
        }
        if (code >= 30 && code <= 38) {
            return "asdfghjkl".substring(code - 30, code - 29);
        }
        if (code >= 44 && code <= 50) {
            return "zxcvbnm".substring(code - 44, code - 43);
        }
        return NAMES.getOrDefault(code, "keyCode_" + code);
    }
}

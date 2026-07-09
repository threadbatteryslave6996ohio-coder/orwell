package dev.orwell.keeboarder.linux;

import java.util.Locale;

final class X11KeyNames {
    private X11KeyNames() {
    }

    static String normalize(String rawName, int keyCode) {
        if (rawName == null || rawName.isBlank()) {
            return "keyCode_" + keyCode;
        }
        if (rawName.length() == 1) {
            return rawName.toLowerCase(Locale.ROOT);
        }
        return switch (rawName) {
            case "Return" -> "return";
            case "BackSpace" -> "backspace";
            case "Escape" -> "escape";
            case "Tab" -> "tab";
            case "space" -> "space";
            case "Shift_L", "Shift_R" -> "shift";
            case "Control_L", "Control_R" -> "control";
            case "Alt_L", "Alt_R", "Meta_L", "Meta_R" -> "alt";
            case "Super_L", "Super_R" -> "super";
            case "Caps_Lock" -> "caps_lock";
            case "Left" -> "left";
            case "Right" -> "right";
            case "Up" -> "up";
            case "Down" -> "down";
            case "semicolon" -> "semicolon";
            case "comma" -> "comma";
            case "period" -> "period";
            case "slash" -> "slash";
            case "minus" -> "minus";
            case "equal" -> "equal";
            case "bracketleft" -> "bracket_left";
            case "bracketright" -> "bracket_right";
            case "apostrophe" -> "apostrophe";
            case "grave" -> "grave";
            case "backslash" -> "backslash";
            default -> rawName.toLowerCase(Locale.ROOT);
        };
    }
}

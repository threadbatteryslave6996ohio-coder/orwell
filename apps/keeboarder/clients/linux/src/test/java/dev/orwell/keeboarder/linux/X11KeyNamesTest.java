package dev.orwell.keeboarder.linux;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class X11KeyNamesTest {
    @Test
    void normalizesSingleCharacterNamesToLowerCase() {
        assertEquals("a", X11KeyNames.normalize("A", 38));
    }

    @Test
    void normalizesCommonModifierAndControlNames() {
        assertEquals("shift", X11KeyNames.normalize("Shift_L", 50));
        assertEquals("control", X11KeyNames.normalize("Control_R", 105));
        assertEquals("alt", X11KeyNames.normalize("Alt_L", 64));
        assertEquals("super", X11KeyNames.normalize("Super_R", 134));
        assertEquals("return", X11KeyNames.normalize("Return", 36));
    }

    @Test
    void fallsBackToKeyCodeWhenKeysymNameIsMissing() {
        assertEquals("keyCode_42", X11KeyNames.normalize("", 42));
    }
}

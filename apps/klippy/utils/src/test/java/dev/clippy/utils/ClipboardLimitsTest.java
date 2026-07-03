package dev.clippy.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipboardLimitsTest {
    @Test
    void acceptsLimitAndRejectsOversizedContent() {
        assertTrue(ClipboardLimits.isWithinContentLimit("x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS)));
        assertFalse(ClipboardLimits.isWithinContentLimit("x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS + 1)));
    }
}

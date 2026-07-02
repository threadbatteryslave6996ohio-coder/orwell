package dev.clippy.clients.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PollIntervalValidatorTest {
    @Test
    void acceptsMinimumValue() {
        assertEquals(100L, PollIntervalValidator.validate(100L, 100L));
    }

    @Test
    void rejectsValuesBelowMinimum() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> PollIntervalValidator.validate(99L, 100L));

        assertEquals("CLIPBOARD_POLL_INTERVAL_MS must be at least 100.", exception.getMessage());
    }
}

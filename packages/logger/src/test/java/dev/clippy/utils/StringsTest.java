package dev.clippy.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StringsTest {
    @Test
    void trimsAndReturnsNonBlankValue() {
        assertEquals("value", Strings.requireNonBlank("  value  ", "field"));
    }

    @Test
    void rejectsNullWithFieldName() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> Strings.requireNonBlank(null, "field"));
        assertEquals("field cannot be blank", exception.getMessage());
    }

    @Test
    void rejectsBlankWithFieldName() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> Strings.requireNonBlank("   ", "field"));
        assertEquals("field cannot be blank", exception.getMessage());
    }
}

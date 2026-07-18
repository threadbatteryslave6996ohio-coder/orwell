package dev.orwell.primitives;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sha256Test {
    @Test
    void producesLowercaseHex() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256.hex("abc".getBytes(StandardCharsets.UTF_8))
        );
    }
}

package dev.clippy.clients.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClipboardEndpointTest {
    @Test
    void normalizesBaseAndClipboardUrls() {
        assertEquals(URI.create("http://localhost:8080/clipboard"), ClipboardEndpoint.from("http://localhost:8080"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), ClipboardEndpoint.from("http://localhost:8080/"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), ClipboardEndpoint.from("http://localhost:8080/clipboard"));
    }

    @Test
    void rejectsBlankUrls() {
        assertThrows(IllegalArgumentException.class, () -> ClipboardEndpoint.from("  "));
    }
}

package dev.orwell.undertow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerRuntimeTest {
    @Test
    void defaultsToSpring() {
        assertEquals(ServerRuntime.Engine.SPRING, ServerRuntime.engine(Map.of()));
    }

    @Test
    void selectsUndertowCaseInsensitively() {
        assertEquals(
                ServerRuntime.Engine.UNDERTOW,
                ServerRuntime.engine(Map.of("SERVER_ENGINE", " Undertow "))
        );
    }

    @Test
    void rejectsUnknownEngine() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerRuntime.engine(Map.of("SERVER_ENGINE", "netty"))
        );
    }
}

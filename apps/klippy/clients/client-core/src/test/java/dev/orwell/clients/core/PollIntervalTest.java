package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollIntervalTest {
    private static Env envWith(Map<String, String> overrides) {
        Map<String, String> source = new LinkedHashMap<>(overrides);
        source.putIfAbsent("REMOTE_SERVER_URL", "http://localhost:8080");
        return ClientEnvs.from(source);
    }

    @Test
    void resolvesToOneSecondWhenIntervalIsUnset() {
        assertEquals(1000L, PollInterval.resolve(envWith(Map.of())));
    }

    @Test
    void resolvesConfiguredInterval() {
        assertEquals(250L, PollInterval.resolve(
                envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "250"))));
    }

    @Test
    void acceptsMinimumValue() {
        assertEquals(100L, PollInterval.resolve(
                envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "100"))));
    }

    @Test
    void clampsIntervalBelowMinimumAndWarns() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertEquals(100L, PollInterval.resolve(
                    envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "50"))));
        } finally {
            System.setErr(original);
        }

        assertTrue(captured.toString(StandardCharsets.UTF_8)
                .contains("CLIPBOARD_POLL_INTERVAL_MS=50 is below the 100 ms minimum"));
    }

    @Test
    void clampsNonPositiveInterval() {
        PrintStream original = System.err;
        System.setErr(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        try {
            assertEquals(100L, PollInterval.resolve(
                    envWith(Map.of("CLIPBOARD_POLL_INTERVAL_MS", "0"))));
        } finally {
            System.setErr(original);
        }
    }
}

package dev.orwell.logging;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleLoggerTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final ConsoleLogger logger = new ConsoleLogger(
            "klippy-mac-client",
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    @Test
    void writesInfoToStandardOut() {
        logger.info("Sent clipboard change.");

        assertTrue(out.toString(StandardCharsets.UTF_8)
                .contains("[klippy-mac-client] [INFO] Sent clipboard change."));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void routesWarnAndErrorToStandardError() {
        logger.warn("Clipboard too large, skipping.");
        logger.error("Cannot reach remote server.");

        String errors = err.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("[WARN] Clipboard too large, skipping."));
        assertTrue(errors.contains("[ERROR] Cannot reach remote server."));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void quotesValuesThatWouldOtherwiseSplitIntoSeveralFields() {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("error", "Connection refused: connect");
        metadata.put("missing", null);

        logger.error("Clipboard send failed.", metadata);

        String errors = err.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("error=\"Connection refused: connect\""), errors);
        assertTrue(errors.contains("missing=null"), errors);
    }

    @Test
    void rendersMetadataAsSortedKeyValuePairs() {
        logger.info("Sent clipboard change.", Map.of("endpoint", "/entries", "clientId", "linux-clip"));

        assertTrue(out.toString(StandardCharsets.UTF_8)
                .contains("Sent clipboard change. clientId=linux-clip endpoint=/entries"));
    }
}

package dev.orwell.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggerModeTest {
    @Test
    void parsesEveryConfiguredSpelling() {
        for (LoggerMode mode : LoggerMode.values()) {
            assertEquals(mode, LoggerMode.parse(mode.configurationValue()));
        }
    }

    @Test
    void forgivesCaseSurroundingSpaceAndUnderscores() {
        assertEquals(LoggerMode.LOKI_WITH_FALLBACK, LoggerMode.parse("LOKI_WITH_FALLBACK"));
        assertEquals(LoggerMode.LOKI_WITH_FALLBACK, LoggerMode.parse("  loki-with-fallback  "));
        assertEquals(LoggerMode.BOTH, LoggerMode.parse("Both"));
    }

    @Test
    void anUnknownValueFailsAndNamesTheLegalOnes() {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> LoggerMode.parse("lokki"));

        // A typo must not quietly become the default: that ships nothing while looking configured.
        assertTrue(failure.getMessage().contains("lokki"));
        assertTrue(failure.getMessage().contains("loki-with-fallback"), failure.getMessage());
    }

    @Test
    void aBlankValueOnlyFallsBackWhereTheCallerAllowsIt() {
        assertEquals(LoggerMode.DISK, LoggerMode.parseOrDefault("", LoggerMode.DISK));
        assertEquals(LoggerMode.DISK, LoggerMode.parseOrDefault(null, LoggerMode.DISK));
        assertThrows(IllegalArgumentException.class, () -> LoggerMode.parse(" "));
    }

    @Test
    void bothWritesEveryRecordToDiskWhileTheFallbackOnlyCatchesWhatLokiMissed() {
        assertTrue(LoggerMode.BOTH.writesEveryRecordToDisk());
        assertFalse(LoggerMode.BOTH.divertsUnshippedRecordsToDisk());

        assertFalse(LoggerMode.LOKI_WITH_FALLBACK.writesEveryRecordToDisk());
        assertTrue(LoggerMode.LOKI_WITH_FALLBACK.divertsUnshippedRecordsToDisk());

        // Both still need a writable file, which is what LoggerSetup opens on.
        assertTrue(LoggerMode.BOTH.usesDisk());
        assertTrue(LoggerMode.LOKI_WITH_FALLBACK.usesDisk());
        assertFalse(LoggerMode.LOKI.usesDisk());
        assertFalse(LoggerMode.CONSOLE.usesDisk());
    }

    @Test
    void everyLokiModeNeedsAnEndpoint() {
        assertTrue(LoggerMode.LOKI.usesLoki());
        assertTrue(LoggerMode.LOKI_WITH_FALLBACK.usesLoki());
        assertTrue(LoggerMode.BOTH.usesLoki());
        assertFalse(LoggerMode.DISK.usesLoki());
        assertFalse(LoggerMode.CONSOLE.usesLoki());
    }
}

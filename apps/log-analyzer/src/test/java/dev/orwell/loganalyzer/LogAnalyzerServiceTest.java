package dev.orwell.loganalyzer;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * How a Loki response that is not shaped the way we expect is treated.
 *
 * <p>Loki stamps each line with epoch nanoseconds as a string, and this used to be parsed
 * unguarded. One malformed entry then aborted the whole poll — and kept aborting it, every
 * interval, for as long as that line stayed inside the lookback window. liveness-analyzer queries
 * the same Grafana through a near-identical copy of this code and had always guarded it; these
 * tests exist so the two cannot drift apart again.
 */
class LogAnalyzerServiceTest {

    /**
     * Loki sends epoch <em>nanoseconds</em>, as a string. Both values are hand-checkable: 0 is the
     * epoch, and 10^18 nanoseconds is 10^9 seconds after it — the "billennium" second, which is
     * 2001-09-09T01:46:40Z. Getting the unit wrong by a factor of a thousand is the mistake this
     * guards, and it would be invisible against a value derived from the code itself.
     */
    @Test
    void parsesLokiNanosecondTimestamps() {
        assertEquals(Instant.EPOCH, LogAnalyzerService.parseNanos("0"));
        assertEquals(Instant.parse("2001-09-09T01:46:40Z"),
                LogAnalyzerService.parseNanos("1000000000000000000"));
    }

    @Test
    void skipsALineWhoseTimestampIsNotANumber() {
        assertNull(LogAnalyzerService.parseNanos("not-a-timestamp"));
    }

    /**
     * The case that actually reaches this code: a null element in the values array becomes the
     * string "null" on the way through {@code String.valueOf}, which is not a number either.
     */
    @Test
    void skipsALineWhoseTimestampIsMissing() {
        assertNull(LogAnalyzerService.parseNanos(null));
    }

    @Test
    void skipsALineWhoseTimestampIsAnObject() {
        assertNull(LogAnalyzerService.parseNanos(java.util.Map.of("unexpected", "shape")));
    }
}

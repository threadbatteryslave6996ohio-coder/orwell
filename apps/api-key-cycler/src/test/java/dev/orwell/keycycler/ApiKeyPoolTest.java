package dev.orwell.keycycler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyPoolTest {
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private static ApiKeyPool pool(String... keys) {
        return new ApiKeyPool(List.of(keys), MINUTE);
    }

    private static List<String> take(ApiKeyPool pool, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> pool.next().value())
                .toList();
    }

    /**
     * Even spread, not drain-then-move-on. A quota is usually per key per window, so holding three
     * keys is meant to be a larger window — which it only is if the requests are shared out.
     */
    @Test
    void keysAreHandedOutInTurnRatherThanDrainingTheFirst() {
        ApiKeyPool pool = pool("a", "b", "c");

        assertEquals(List.of("a", "b", "c", "a", "b", "c"), take(pool, 6));
    }

    /**
     * The whole point of reacting to a 429. Without this the refused key comes round again on the
     * very next request and earns the same refusal, so the pool's throughput collapses to whatever
     * the healthy keys happen to pick up.
     */
    @Test
    void aRestingKeyIsSkippedUntilItsCooldownExpires() {
        ApiKeyPool pool = pool("a", "b", "c");

        pool.cool(new ApiKey(2, "b"));

        assertFalse(take(pool, 6).contains("b"), "a resting key must not be handed out");
    }

    /** A zero cooldown is a pool that spreads load and never reacts — legal, and not a special case. */
    @Test
    void aZeroCooldownRestsNothing() {
        ApiKeyPool pool = new ApiKeyPool(List.of("a", "b"), Duration.ZERO);

        pool.cool(new ApiKey(1, "a"));

        assertTrue(take(pool, 4).contains("a"));
    }

    /**
     * An exhausted pool still answers. A synthetic 503 from this proxy would tell the client less
     * than the upstream's own 429 — which carries its Retry-After — and would make the proxy the
     * thing that broke.
     */
    @Test
    void whenEveryKeyIsRestingTheOneReadySoonestIsUsedRatherThanRefusingTheRequest() {
        ApiKeyPool pool = new ApiKeyPool(List.of("a", "b"), Duration.ofMillis(50));

        // `a` is rested first, so it recovers first.
        pool.cool(new ApiKey(1, "a"));
        pool.cool(new ApiKey(2, "b"));

        ApiKey chosen = pool.next();
        assertNotNull(chosen);
        assertEquals("a", chosen.value());
    }

    @Test
    void blankEntriesAreDroppedAndAnEmptyPoolIsRefused() {
        assertEquals(2, new ApiKeyPool(List.of("a", "  ", "b", ""), MINUTE).size());
        // Forwarding every request with no credential at all only produces 401s the client cannot
        // act on, so this fails at startup instead.
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyPool(List.of("", " "), MINUTE));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyPool.fromConfiguration("", MINUTE));
    }

    @Test
    void configuredKeysAreCommaSeparatedAndTrimmed() {
        ApiKeyPool pool = ApiKeyPool.fromConfiguration(" first , second ", MINUTE);

        assertEquals(List.of("first", "second"), take(pool, 2));
    }

    /** One key is a static credential, and the filter uses this to know not to bother retrying. */
    @Test
    void aPoolOfOneReportsThatThereIsNothingToRotateTo() {
        assertFalse(pool("only").canRotate());
        assertTrue(pool("a", "b").canRotate());
    }

    /**
     * A proxy that cycles keys is the process holding every key at once, so what it says about
     * itself is where they would leak. Positions and counts, never values — in the fingerprint that
     * reaches an audit column, and in the health details that reach an HTTP response.
     */
    @Test
    void nothingThePoolSaysAboutItselfContainsAKey() {
        ApiKeyPool pool = pool("super-secret-one", "super-secret-two");
        pool.cool(new ApiKey(1, "super-secret-one"));

        String signature = pool.signature();
        Map<String, Object> health = pool.healthDetails();

        assertFalse(signature.contains("super-secret"), () -> "key leaked into the signature: " + signature);
        assertFalse(health.toString().contains("super-secret"), () -> "key leaked into health: " + health);
        assertEquals(2, health.get("keys"));
        assertEquals(List.of("key #1"), health.get("resting"));
    }

    /** Including the default record toString, which is the easiest way for one to reach a log. */
    @Test
    void printingAKeyPrintsItsPositionRatherThanItsValue() {
        ApiKey key = new ApiKey(3, "super-secret");

        assertEquals("key #3", key.toString());
        assertEquals("key #3", key.label());
    }

    /** Adding or removing a key changes the configuration, so it has to change the fingerprint. */
    @Test
    void resizingThePoolChangesWhatTheFingerprintSaysWasInForce() {
        assertFalse(pool("a", "b").signature().equals(pool("a", "b", "c").signature()));
    }
}

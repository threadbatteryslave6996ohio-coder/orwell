package dev.orwell.insta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped default for the spend guard. This deployment needs at most 500 accounts per lookup,
 * and every account past that is billed for nothing — so the ceiling is pinned here rather than
 * left to whoever next edits {@link InstaEnvs}. Raising it is a deliberate act, not a typo.
 */
class InstaEnvsTest {

    @Test
    void capsAConnectionsLookupAtFiveHundredAccountsByDefault() {
        assertThat(InstaEnvs.INSTA_MAX_LIMIT.defaultValue()).contains(500);
    }

    /**
     * The default sits at the ceiling: a lookup that finishes a list is cached as the whole list
     * and answers every later request for it, so a smaller default mostly buys a second run.
     */
    @Test
    void defaultsToTheCeilingWhenTheCallerAsksForNoLimit() {
        assertThat(InstaEnvs.INSTA_DEFAULT_LIMIT.defaultValue()).contains(500);
    }
}

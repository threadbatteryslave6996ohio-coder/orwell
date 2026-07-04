package dev.orwell.auth.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAuthenticationStrategyTest {
    @Test
    void registersValidatesAndRevokesTokens() {
        var strategy = new InMemoryAuthenticationStrategy();

        strategy.register("client-a", "token-a");
        assertTrue(strategy.isTokenValidForClient("client-a", "token-a"));
        assertFalse(strategy.isTokenValidForClient("client-a", "wrong"));

        strategy.revoke("client-a");
        assertFalse(strategy.isTokenValidForClient("client-a", "token-a"));
    }
}

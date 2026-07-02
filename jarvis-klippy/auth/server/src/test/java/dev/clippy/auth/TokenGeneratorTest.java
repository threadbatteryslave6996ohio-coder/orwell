package dev.clippy.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenGeneratorTest {
    private final TokenGenerator tokenGenerator = new TokenGenerator();

    @Test
    void producesUrlSafeBase64TokensWithoutPadding() {
        String token = tokenGenerator.newToken();

        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
    }

    @Test
    void returnsDifferentTokensAcrossCalls() {
        String first = tokenGenerator.newToken();
        String second = tokenGenerator.newToken();

        assertNotEquals(first, second);
    }
}

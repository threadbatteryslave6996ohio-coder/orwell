package dev.clippy.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialHasherTest {
    private final CredentialHasher hasher = new CredentialHasher();

    @Test
    void hashesSecretsAndVerifiesThem() {
        String hash = hasher.hashSecret("super-secret");

        assertTrue(hash.startsWith("pbkdf2$120000$"));
        assertTrue(hasher.matches("super-secret", hash));
        assertFalse(hasher.matches("wrong-secret", hash));
    }

    @Test
    void hashesTokensAsStableHexDigests() {
        String first = hasher.hashToken("token-value");
        String second = hasher.hashToken("token-value");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }
}

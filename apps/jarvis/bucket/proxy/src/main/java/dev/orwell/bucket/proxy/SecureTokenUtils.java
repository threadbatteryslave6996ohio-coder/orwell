package dev.orwell.bucket.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class SecureTokenUtils {
    private SecureTokenUtils() {
    }

    static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}

package dev.orwell.bucket.proxy;

import java.nio.charset.StandardCharsets;

final class SecureTokenUtils {
    private SecureTokenUtils() {
    }

    static boolean constantTimeEquals(String left, String right) {
        byte[] l = left.getBytes(StandardCharsets.UTF_8);
        byte[] r = right.getBytes(StandardCharsets.UTF_8);
        if (l.length != r.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < l.length; i++) {
            diff |= l[i] ^ r[i];
        }
        return diff == 0;
    }
}

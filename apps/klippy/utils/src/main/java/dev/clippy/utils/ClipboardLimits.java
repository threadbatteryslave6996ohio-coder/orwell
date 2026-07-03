package dev.clippy.utils;

public final class ClipboardLimits {
    public static final int MAX_CONTENT_CHARACTERS = 1_000_000;

    private ClipboardLimits() {
    }

    public static boolean isWithinContentLimit(String content) {
        return content != null && content.length() <= MAX_CONTENT_CHARACTERS;
    }
}

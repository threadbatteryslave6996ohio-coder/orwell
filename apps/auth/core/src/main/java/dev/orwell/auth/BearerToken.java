package dev.orwell.auth;

public final class BearerToken {
    private static final String PREFIX = "Bearer ";

    private BearerToken() {}

    public static String extract(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(PREFIX.length()).trim();
        return token.isBlank() ? null : token;
    }
}

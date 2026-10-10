package dev.orwell.http;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The id that ties one request's records together across services.
 *
 * <p>Without it, a proxy row and the upstream's own log lines describe the same request and nothing
 * joins them: you can see that a request was forwarded and that something failed a layer down, but
 * not that they are the same request. Every service stamps {@value #HEADER} on what it sends and
 * records what it received, so one id spans the hop.
 *
 * <p>An inbound id is <strong>accepted, not trusted</strong>. {@link #resolve(String)} keeps a
 * caller's id when it is short and boring, and mints a fresh one otherwise — the value lands in log
 * lines and in a database column, so an unbounded or newline-carrying header would be a log
 * injection with extra steps.
 *
 * <p>{@link #current()} is the ambient id for the thread handling a request, set by the server's
 * inbound filter and read by {@link ServiceHeaderHttpClient} when it sends. That covers this repo's
 * request paths, which are thread-per-request; work handed to a pool or a queue leaves the scope
 * behind, so anything asynchronous has to carry the id itself.
 */
public final class RequestId {
    /** The header carrying the id. Case-insensitive on the wire, like every header name. */
    public static final String HEADER = "X-Request-Id";

    /**
     * Longest inbound id kept. Long enough for a UUID with dashes, short enough that a caller
     * cannot use the field as free storage in someone else's audit table.
     */
    static final int MAX_LENGTH = 64;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestId() {
    }

    /** A fresh id: 16 hex characters, which is short enough to read in a log line and paste. */
    public static String mint() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * The caller's id if it is usable, a fresh one otherwise. Usable means non-blank, at most
     * {@value #MAX_LENGTH} characters, and made only of characters that are safe in a log line and
     * meaningless in SQL: letters, digits, {@code .}, {@code _}, {@code :} and {@code -}.
     */
    public static String resolve(String inbound) {
        String sanitized = sanitize(inbound);
        return sanitized == null ? mint() : sanitized;
    }

    /** The usable form of {@code candidate}, or null when there is none. */
    public static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (!isAllowed(trimmed.charAt(index))) {
                return null;
            }
        }
        return trimmed;
    }

    /** The id for the request this thread is handling, or null outside a request. */
    public static String current() {
        return CURRENT.get();
    }

    /** Binds {@code id} to this thread. Callers must {@link #clear()} in a finally block. */
    public static void set(String id) {
        if (id == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(id);
        }
    }

    /**
     * Unbinds the thread's id. Not optional on a pooled thread: a leftover id would attach the
     * next request's records to the previous request, which is worse than having no id at all.
     */
    public static void clear() {
        CURRENT.remove();
    }

    private static boolean isAllowed(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '_' || character == ':' || character == '-';
    }
}

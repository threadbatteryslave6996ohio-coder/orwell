package dev.orwell.keycycler;

import dev.orwell.logging.Logger;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Puts a key on the way out, and reads the answer on the way back.
 *
 * <p>{@code before} takes the next key from the {@link ApiKeyPool} and writes it onto the request,
 * replacing whatever the client sent under that header. {@code after} looks at the status: one of
 * {@code CYCLER_ROTATE_ON_STATUSES} means this key is spent for now, so it is rested and the
 * request goes round again with the next one.
 *
 * <p>The key it used is remembered on the {@link Attempt}, not in a field. One filter bean serves
 * every request thread, so a field would have two requests resting each other's keys under any
 * load at all — which reads as a pool that mysteriously cools keys that never failed.
 *
 * <p>It must be thread-safe and must not block: {@link #before} and {@link #after} both run on the
 * request path.
 *
 * <p><strong>What is never on a log line is the key.</strong> Records name {@link ApiKey#label()},
 * the position in the pool. That is enough to answer "which key is being rate-limited?" — the
 * question anyone actually asks — and it keeps the secret out of the log stream, the audit table,
 * and {@code /health}, which is where a proxy holding every key at once would otherwise leak them.
 */
public final class ApiKeyFilter {
    /** Where {@code before} leaves the key for {@code after}. */
    private static final String NOTE = "api-key";

    private final ApiKeyPool pool;
    private final String header;
    private final String scheme;
    private final Set<Integer> rotateOn;
    private final Logger logger;

    public ApiKeyFilter(ApiKeyPool pool, String header, String scheme, Set<Integer> rotateOn, Logger logger) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.header = requireText(header, "header");
        // Blank is legal and is what an X-API-Key style header wants: the value is the bare key.
        this.scheme = scheme == null ? "" : scheme.trim();
        this.rotateOn = Set.copyOf(Objects.requireNonNull(rotateOn, "rotateOn"));
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Parses the comma-separated {@code CYCLER_ROTATE_ON_STATUSES} form. An empty list is legal and
     * means "never rotate on an answer" — the pool still spreads requests round-robin, it just
     * stops reacting to what comes back.
     */
    public static Set<Integer> statuses(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    int status = Integer.parseInt(value);
                    if (status < 100 || status > 599) {
                        throw new IllegalArgumentException("Not an HTTP status: " + value);
                    }
                    return status;
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    public String name() {
        return "api-key-cycler";
    }

    /**
     * The request as it should go upstream: the next key from the pool, written onto the configured
     * header. Called once per attempt, so a retry gets a different key.
     */
    public ProxyRequest before(ProxyRequest request, Attempt attempt) {
        ApiKey key = pool.next();
        attempt.note(NOTE, key);
        return request.withHeader(header, value(key));
    }

    /**
     * What to do with the upstream's answer. {@code request} is the one {@link #before} produced,
     * not the one the client sent.
     */
    public Retry after(ProxyRequest request, ProxyResponse response, Attempt attempt) {
        if (!rotateOn.contains(response.status())) {
            return Retry.no();
        }
        ApiKey key = (ApiKey) attempt.note(NOTE);
        if (key == null) {
            // before() always notes one, so this is unreachable — and if it ever is reached, the
            // wrong thing to do is guess which key to rest.
            return Retry.no();
        }
        pool.cool(key);
        logger.warn("An API key was refused; resting it.", Map.of(
                "requestId", request.requestId(),
                "key", key.label(),
                "status", response.status(),
                "attempt", attempt.number()));
        if (!pool.canRotate()) {
            // One key is not a pool. Retrying would send the same credential to the same upstream
            // and get the same answer, one round-trip later.
            return Retry.no();
        }
        return Retry.yes("upstream answered " + response.status() + " for " + key.label());
    }

    /**
     * The keys are not in here, only how many and what governs them. See
     * {@link ApiKeyPool#signature()} — this reaches a log line.
     */
    public String signature() {
        return name() + "(" + header + "," + pool.signature()
                + ",rotateOn=" + new TreeSet<>(rotateOn) + ")";
    }

    /** What the pool looks like, for {@code /health}. */
    public Map<String, Object> healthDetails() {
        return pool.healthDetails();
    }

    private String value(ApiKey key) {
        return scheme.isEmpty() ? key.value() : scheme + " " + key.value();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}

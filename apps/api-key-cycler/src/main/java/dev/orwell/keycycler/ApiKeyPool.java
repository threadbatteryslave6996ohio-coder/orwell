package dev.orwell.keycycler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The keys, and which of them are currently resting.
 *
 * <p>Two rules, and they are the whole class:
 *
 * <ol>
 *   <li><strong>Round-robin by default.</strong> Requests are spread evenly rather than draining
 *       key #1 until it complains, because a quota is usually per-key per-window and the point of
 *       holding several is to have a larger window, not a longer queue on the first one.
 *   <li><strong>A key that just refused us is skipped for a while.</strong> Without this, cycling
 *       is a bicycle with a flat: a rate-limited key comes round again on the next request and
 *       gets the same 429, so the pool's throughput collapses to whatever the healthy keys pick up
 *       by luck. {@link #cool} is what {@link ApiKeyFilter} calls when the upstream refuses.
 * </ol>
 *
 * <p><strong>An exhausted pool still hands out a key.</strong> When every key is cooling, this
 * returns the one whose rest ends soonest rather than refusing the request. That is deliberate: a
 * synthetic 503 from this proxy tells the client less than the upstream's own 429 — which carries
 * its {@code Retry-After} and its own explanation — and it makes the proxy the thing that broke.
 * Relaying the truth beats inventing an error.
 *
 * <p>Thread-safe and lock-free: the cursor and the cooldown deadlines are the only mutable state,
 * and both are atomics. Two requests racing may take the same key or skip one; neither costs
 * anything, and paying for a lock on every request to prevent it would.
 */
public final class ApiKeyPool {
    private final List<String> keys;
    private final Duration cooldown;
    /** Wall-clock nanos, from {@link System#nanoTime}, until which each key is resting. */
    private final AtomicLongArray restingUntil;
    private final AtomicInteger cursor = new AtomicInteger();

    /** What {@code HttpRequest.Builder.header} will accept: visible US-ASCII, plus tab and space. */
    private static boolean isValidHeaderValue(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean printable = character >= 0x20 && character <= 0x7E;
            if (!printable && character != '\t') {
                return false;
            }
        }
        return true;
    }

    public ApiKeyPool(List<String> keys, Duration cooldown) {
        List<String> cleaned = new ArrayList<>();
        int position = 0;
        for (String key : Objects.requireNonNull(keys, "keys")) {
            position++;
            if (key != null && !key.isBlank()) {
                String trimmed = key.trim();
                // Checked here so it cannot be discovered later by the JDK. HttpRequest.Builder
                // refuses a header value containing a control character or anything above U+00FF —
                // a stray newline or a smart quote from a copy-paste — and its IllegalArgumentException
                // embeds *the value*. That exception is caught per request and logged, so one
                // unusable key would write itself into the log on every request that drew it,
                // defeating ApiKey.toString(). Fail at startup instead, naming the position only.
                if (!isValidHeaderValue(trimmed)) {
                    throw new IllegalArgumentException(
                            "API key #" + position + " contains characters that cannot be sent in an "
                                    + "HTTP header (control characters, or anything above U+00FF). "
                                    + "The value is not shown.");
                }
                cleaned.add(trimmed);
            }
        }
        if (cleaned.isEmpty()) {
            // Starting with no keys would mean forwarding every request unauthenticated, which the
            // upstream answers with a 401 the client cannot act on. Refuse to start instead.
            throw new IllegalArgumentException("at least one API key is required");
        }
        this.keys = List.copyOf(cleaned);
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.restingUntil = new AtomicLongArray(this.keys.size());
        long now = System.nanoTime();
        for (int index = 0; index < this.keys.size(); index++) {
            // Every key starts available. Zero would work as a sentinel on most JVMs and not on
            // one whose nanoTime starts negative, which is permitted and does happen.
            restingUntil.set(index, now);
        }
    }

    /** Parses the comma-separated {@code CYCLER_API_KEYS} form. */
    public static ApiKeyPool fromConfiguration(String configured, Duration cooldown) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException("CYCLER_API_KEYS must list at least one key");
        }
        return new ApiKeyPool(List.of(configured.split(",")), cooldown);
    }

    public int size() {
        return keys.size();
    }

    /** Whether there is anything to rotate <em>to</em>. A pool of one is a static credential. */
    public boolean canRotate() {
        return keys.size() > 1;
    }

    /**
     * The next key to try: the first one from the cursor that is not resting, or — when all of them
     * are — the one that will be ready soonest.
     */
    public ApiKey next() {
        long now = System.nanoTime();
        int start = Math.floorMod(cursor.getAndIncrement(), keys.size());
        for (int offset = 0; offset < keys.size(); offset++) {
            int index = (start + offset) % keys.size();
            // Subtraction, never `<`: nanoTime is only meaningful as a difference, and comparing
            // the values directly breaks when the clock's origin puts them either side of the
            // long boundary.
            if (restingUntil.get(index) - now <= 0) {
                return key(index);
            }
        }
        return key(soonestReady());
    }

    /**
     * Rests a key that the upstream just refused. Idempotent in effect — two requests that both
     * got a 429 from the same key simply set the same deadline twice.
     */
    public void cool(ApiKey key) {
        Objects.requireNonNull(key, "key");
        restingUntil.set(key.position() - 1, System.nanoTime() + cooldown.toNanos());
    }

    /** How the pool looks right now, for {@code /health}. Counts and positions only. */
    public Map<String, Object> healthDetails() {
        long now = System.nanoTime();
        List<String> resting = new ArrayList<>();
        for (int index = 0; index < keys.size(); index++) {
            if (restingUntil.get(index) - now > 0) {
                resting.add(key(index).label());
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("keys", keys.size());
        details.put("cooldownSeconds", cooldown.toSeconds());
        details.put("resting", resting);
        return details;
    }

    /**
     * What the audit fingerprint digests. The <em>count</em> and the cooldown, never the keys: a
     * fingerprint ends up in a log line and a database column, and it only has to change when the
     * configuration does — which adding or removing a key does.
     */
    public String signature() {
        return "keys=" + keys.size() + ",cooldown=" + cooldown.toSeconds() + "s";
    }

    private int soonestReady() {
        int soonest = 0;
        for (int index = 1; index < keys.size(); index++) {
            if (restingUntil.get(index) - restingUntil.get(soonest) < 0) {
                soonest = index;
            }
        }
        return soonest;
    }

    private ApiKey key(int index) {
        return new ApiKey(index + 1, keys.get(index));
    }
}

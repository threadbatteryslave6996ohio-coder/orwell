package dev.orwell.insta.instagram;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.InstaJson;
import dev.orwell.insta.apify.ActorRun;
import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.insta.cache.ScrapeCache;
import dev.orwell.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns an Instagram username into an Apify actor run and the run's dataset items into this
 * program's value types — and, wherever it can, into a cache read instead.
 *
 * <p>Two actors, because the two questions cost different amounts: the profile actor answers
 * "how many followers?" with a single dataset item, while the connections actor is billed per
 * account it returns. Asking the expensive one for a count would be a waste of the caller's Apify
 * credit, so {@link #profile} and {@link #connections} stay separate calls.
 *
 * <p>Every answer is cached under a key that includes everything which changes it — username,
 * direction, page size, cursor — so a repeated walk of the same list costs nothing. The cache is
 * consulted before Apify and written after; a cache failure just means paying for the scrape.
 *
 * <p>Upstream failures propagate as {@link ApifyException}, which already distinguishes a timeout
 * from an exhausted balance from a broken actor. A missing account is a
 * {@link ProfileNotFoundException} and an impossible username an {@link IllegalArgumentException} —
 * three different things a caller can act on differently, kept apart rather than flattened.
 */
public class InstagramService {
    /** Instagram's own rule: letters, digits, periods and underscores, up to 30 characters. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._]{1,30}");
    /** The default connections actor rejects a smaller {@code resultsLimit}. */
    private static final int MIN_RESULTS_LIMIT = 50;
    /** Bump when a cached shape changes, so old entries are ignored rather than mis-read. */
    private static final String CACHE_VERSION = "v2";

    private final ApifyClient apify;
    private final ScrapeCache cache;
    private final String profileActor;
    private final String connectionsActor;
    private final int defaultLimit;
    private final int maxLimit;
    private final Logger logger;

    public InstagramService(
            ApifyClient apify,
            ScrapeCache cache,
            String profileActor,
            String connectionsActor,
            int defaultLimit,
            int maxLimit,
            Logger logger) {
        this.apify = Objects.requireNonNull(apify, "apify");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.profileActor = Objects.requireNonNull(profileActor, "profileActor");
        this.connectionsActor = Objects.requireNonNull(connectionsActor, "connectionsActor");
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * The account's profile, including its follower and following counts.
     *
     * @throws ProfileNotFoundException when the actor returns no item for the username — the
     *                                  account does not exist, or it is private enough that the
     *                                  actor cannot see it.
     */
    public InstagramProfile profile(String rawUsername) {
        String username = normalizedUsername(rawUsername);
        String key = CACHE_VERSION + ":profile:" + username;

        Optional<InstagramProfile> cached = read(key, new TypeReference<InstagramProfile>() {
        });
        if (cached.isPresent()) {
            logger.info("Served an Instagram profile from cache.", Map.of("username", username));
            return cached.get();
        }

        List<JsonNode> items = runSync(profileActor, Map.of("usernames", List.of(username)), 1, username);
        if (items.isEmpty()) {
            // Deliberately not cached: an account that is new, renamed, or briefly unreachable
            // would otherwise stay "missing" for a whole day.
            throw new ProfileNotFoundException(
                    "No public Instagram profile was found for that username.");
        }
        InstagramProfile profile = InstagramProfile.from(items.get(0), username);
        write(key, profile);

        // followersCount is nullable and Map.of is not, so this metadata map has to tolerate nulls.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("username", username);
        metadata.put("followersCount", profile.followersCount());
        metadata.put("followingCount", profile.followingCount());
        logger.info("Fetched an Instagram profile.", metadata);
        return profile;
    }

    /**
     * One page of the accounts following {@code rawUsername}, or of the accounts it follows.
     *
     * <p>{@code cursor} is null for the first page and otherwise the {@code nextCursor} of the page
     * before it. A returned {@code nextCursor} of null means the actor reported no more accounts;
     * a full page with no cursor is the end of the list, not a truncation.
     *
     * <p>An empty first page is a success, not a 404: a private account and an account with no
     * followers are indistinguishable from here, and the actor reports both as an empty dataset.
     * Callers who need to tell them apart should read {@link #profile}'s {@code isPrivate}.
     */
    public ConnectionsPage connections(
            String rawUsername, ConnectionType type, Integer requestedLimit, String cursor) {
        String username = normalizedUsername(rawUsername);
        int limit = boundedLimit(requestedLimit);
        String continuation = cursor == null || cursor.isBlank()
                ? null
                : ConnectionCursor.tokenFor(cursor, username, type);

        String key = CACHE_VERSION + ":" + type.name().toLowerCase(Locale.ROOT) + ":" + username
                + ":" + limit + ":" + fingerprint(continuation);
        Optional<ConnectionsPage> cached = read(key, new TypeReference<ConnectionsPage>() {
        });
        if (cached.isPresent()) {
            logger.info("Served Instagram connections from cache.", Map.of(
                    "username", username, "type", type.name(), "count",
                    cached.get().accounts().size()));
            return cached.get();
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Account", List.of(username));
        input.put("dataToScrape", type.actorValue());
        // The actor floors this; `maxItems` on the run is what actually enforces `limit`.
        input.put("resultsLimit", Math.max(limit, MIN_RESULTS_LIMIT));
        if (continuation != null) {
            input.put("continuationToken", continuation);
        }

        ActorRun run = runForOutput(connectionsActor, input, limit, username);
        List<InstagramAccount> accounts = accountsIn(run.items(), username, type);
        String nextToken = ConnectionCursor.nextTokenIn(run.output());
        ConnectionsPage page = new ConnectionsPage(accounts, nextToken == null
                ? null
                : new ConnectionCursor(username, type, nextToken).encode());
        write(key, page);

        logger.info("Fetched Instagram connections.", Map.of(
                "username", username, "type", type.name(), "count", accounts.size(),
                "limit", limit, "hasMore", page.nextCursor() != null));
        return page;
    }

    /** {@code limit} clamped into 1..{@code INSTA_MAX_LIMIT}, defaulting when absent or invalid. */
    public int boundedLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultLimit, maxLimit);
        }
        return Math.min(requested, maxLimit);
    }

    private List<InstagramAccount> accountsIn(
            List<JsonNode> items, String username, ConnectionType type) {
        List<InstagramAccount> accounts = new ArrayList<>(items.size());
        int unusable = 0;
        for (JsonNode item : items) {
            InstagramAccount account = InstagramAccount.from(item);
            if (account == null) {
                unusable++;
                continue;
            }
            accounts.add(account);
        }
        if (unusable > 0) {
            // Rows with no username mean the actor's output shape has drifted from what we map.
            logger.warn("Skipped Apify dataset items with no username.", Map.of(
                    "username", username, "type", type.name(), "skipped", unusable));
        }
        return accounts;
    }

    private List<JsonNode> runSync(
            String actor, Map<String, Object> input, int maxItems, String username) {
        try {
            return apify.runActor(actor, input, maxItems);
        } catch (ApifyException exception) {
            throw logged(exception, actor, username);
        }
    }

    private ActorRun runForOutput(
            String actor, Map<String, Object> input, int maxItems, String username) {
        try {
            return apify.runActorWithOutput(actor, input, maxItems);
        } catch (ApifyException exception) {
            throw logged(exception, actor, username);
        }
    }

    /**
     * Upstream failures are rethrown as they arrive. {@link ApifyException.Kind} already says what
     * went wrong and whether it is worth retrying, so wrapping it would only lose that — the one
     * thing worth adding here is which lookup provoked it.
     */
    private ApifyException logged(ApifyException exception, String actor, String username) {
        logger.error("Instagram lookup failed upstream.", Map.of(
                "username", username, "actor", actor, "kind", exception.kind().name(),
                "error", exception.toString()));
        return exception;
    }

    /** A cached entry we cannot read is a miss: never let stale JSON take a request down. */
    private <T> Optional<T> read(String key, TypeReference<T> type) {
        Optional<String> stored = cache.find(key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(InstaJson.mapper().readValue(stored.get(), type));
        } catch (Exception exception) {
            logger.warn("Ignored an unreadable cache entry.", Map.of("key", key));
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        try {
            cache.store(key, InstaJson.mapper().writeValueAsString(value));
        } catch (Exception exception) {
            logger.warn("Could not serialize a value for the cache.", Map.of("key", key));
        }
    }

    /**
     * Continuation tokens run to hundreds of characters, so the cache key carries a digest of one
     * rather than the token itself — same identity, bounded key length.
     */
    private static String fingerprint(String continuation) {
        if (continuation == null) {
            return "first";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(continuation.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /**
     * Instagram usernames are case-insensitive and often pasted with the leading {@code @}, so
     * both are accepted. Anything else is rejected here rather than passed through: an unvalidated
     * username would be forwarded verbatim into a third-party actor's input.
     */
    public static String normalizedUsername(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (username.startsWith("@")) {
            username = username.substring(1);
        }
        username = username.toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Not a valid Instagram username: " + rawUsername);
        }
        return username;
    }
}

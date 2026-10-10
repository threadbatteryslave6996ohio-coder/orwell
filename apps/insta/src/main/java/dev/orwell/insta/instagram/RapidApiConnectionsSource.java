package dev.orwell.insta.instagram;

import dev.orwell.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Any of the Instagram APIs on RapidAPI.
 *
 * <p>RapidAPI is a marketplace, not an API — there are dozens of Instagram endpoints on it, they
 * are priced and shaped differently, and they appear and vanish. So this source is configured
 * rather than hardcoded: the host, the two paths and the query parameter names all come from the
 * environment, and the response is read leniently by {@link JsonHttpConnectionsSource}. Switching
 * to a different vendor is meant to be a change to {@code .env}, not to this file.
 *
 * <pre>
 * GET https://{INSTA_RAPIDAPI_HOST}{path}?{userParam}=nasa&amp;{limitParam}=500[&amp;{cursorParam}=…]
 * X-RapidAPI-Key:  {INSTA_RAPIDAPI_KEY}
 * X-RapidAPI-Host: {INSTA_RAPIDAPI_HOST}
 * </pre>
 *
 * <p>The defaults match the common {@code instagram-scraper-api2} shape and are a starting point,
 * not a promise — check your vendor's docs and override what differs.
 *
 * <p><b>Pagination is off by default</b> ({@code INSTA_RAPIDAPI_PAGINATES}). Most of these
 * endpoints cap out at a page and hand back nothing to resume from, and claiming otherwise is the
 * expensive kind of wrong here: a source that cannot prove it reached the end of a list must never
 * be allowed to end one, or the first short page retires everybody it did not include. Left off,
 * this source can add followers and never retire one.
 *
 * <p>Following lists are likewise off by default: plenty of these vendors sell only a followers
 * endpoint, and a chain must skip a source for a direction it cannot read rather than send a
 * request it will answer with something else.
 */
public final class RapidApiConnectionsSource extends JsonHttpConnectionsSource {
    public static final String NAME = "rapidapi";

    private final String baseUrl;
    private final String host;
    private final String key;
    private final String followersPath;
    private final String followingPath;
    private final String userParam;
    private final String limitParam;
    private final String cursorParam;
    private final boolean paginates;
    private final boolean readsFollowing;
    private final Duration requestTimeout;

    public RapidApiConnectionsSource(Settings settings, Logger logger) {
        super(NAME, logger, Duration.ofSeconds(10));
        Objects.requireNonNull(settings, "settings");
        if (settings.key() == null || settings.key().isBlank()) {
            throw new IllegalArgumentException(
                    "The rapidapi connections source needs INSTA_RAPIDAPI_KEY.");
        }
        if (settings.host() == null || settings.host().isBlank()) {
            throw new IllegalArgumentException(
                    "The rapidapi connections source needs INSTA_RAPIDAPI_HOST.");
        }
        // A bare hostname gets https, which is what RapidAPI is. A host that already carries a
        // scheme is taken as given, so a local mock or an outbound proxy can be pointed at without
        // a test-only switch — the gateway header stays the bare host either way.
        this.host = stripScheme(settings.host());
        this.baseUrl = settings.host().startsWith("http://") || settings.host().startsWith("https://")
                ? stripTrailingSlash(settings.host())
                : "https://" + this.host;
        this.key = settings.key();
        this.followersPath = settings.followersPath();
        this.followingPath = settings.followingPath();
        this.userParam = settings.userParam();
        this.limitParam = settings.limitParam();
        this.cursorParam = settings.cursorParam();
        this.paginates = settings.paginates();
        this.readsFollowing = settings.readsFollowing();
        this.requestTimeout = settings.requestTimeout();
    }

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.FOLLOWERS || readsFollowing;
    }

    @Override
    public boolean supportsCursor() {
        return paginates;
    }

    @Override
    protected URI uri(String username, ConnectionType type, int limit, String cursor) {
        String path = type == ConnectionType.FOLLOWING ? followingPath : followersPath;
        StringBuilder url = new StringBuilder(baseUrl)
                .append(path.startsWith("/") ? path : "/" + path)
                .append(path.contains("?") ? "&" : "?")
                .append(userParam).append("=").append(encode(username))
                .append("&").append(limitParam).append("=").append(limit);
        if (cursor != null && !cursor.isBlank()) {
            url.append("&").append(cursorParam).append("=").append(encode(cursor));
        }
        return URI.create(url.toString());
    }

    @Override
    protected Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-RapidAPI-Key", key);
        // Sent as well as being the hostname: RapidAPI's gateway routes on the header, and some
        // vendors reject a request that omits it even when the URL is right.
        headers.put("X-RapidAPI-Host", host);
        return headers;
    }

    @Override
    protected Duration requestTimeout() {
        return requestTimeout;
    }

    private static String stripScheme(String value) {
        String withoutScheme = value.replaceFirst("^https?://", "");
        return stripTrailingSlash(withoutScheme);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Everything a vendor might disagree about. A record rather than eleven constructor arguments,
     * and with defaults on {@link #of} so a working configuration is two environment variables.
     */
    public record Settings(
            String host, String key, String followersPath, String followingPath, String userParam,
            String limitParam, String cursorParam, boolean paginates, boolean readsFollowing,
            Duration requestTimeout) {

        public static Settings of(
                String host, String key, String followersPath, String followingPath,
                String userParam, String limitParam, String cursorParam, boolean paginates,
                boolean readsFollowing, int timeoutSeconds) {
            return new Settings(
                    host, key,
                    blankTo(followersPath, "/v1/followers"),
                    blankTo(followingPath, "/v1/following"),
                    blankTo(userParam, "username_or_id_or_url"),
                    blankTo(limitParam, "count"),
                    blankTo(cursorParam, "pagination_token"),
                    paginates, readsFollowing,
                    Duration.ofSeconds(Math.max(timeoutSeconds, 1)));
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}

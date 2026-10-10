package dev.orwell.insta.instagram;

import dev.orwell.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Your own scraper, wherever you are running it.
 *
 * <p>The only source here whose contract is <em>ours</em> rather than a third party's, so it is the
 * strict one: both directions, real pagination, and a shape that is specified rather than guessed
 * at. Everything else in the chain is an endpoint we adapt to; this is one that adapts to us.
 *
 * <pre>
 * GET {INSTA_SELFHOSTED_URL}/connections?username=nasa&amp;type=followers&amp;limit=500[&amp;cursor=…]
 * Authorization: Bearer {INSTA_SELFHOSTED_TOKEN}      (sent only when the token is set)
 *
 * 200 {"accounts": [{"id": "1", "username": "alice", "fullName": "Alice"}, …],
 *      "nextCursor": "…"}          // null or absent = the list is exhausted
 * </pre>
 *
 * <p><b>{@code nextCursor} is load-bearing and must be honest.</b> Absent means "there is no more",
 * and {@code sync} takes that as licence to retire every follower it did not see this walk. A
 * service that truncates a page without handing back a cursor will look exactly like a mass
 * unfollow. If yours cannot paginate, say so with {@code INSTA_SELFHOSTED_PAGINATES=false} and it
 * will be allowed to add followers but never to retire one.
 *
 * <p>Account fields are read through {@link DatasetFields}, so {@code full_name} and
 * {@code fullName} are both fine; only {@code username} is required per row.
 */
public final class SelfHostedConnectionsSource extends JsonHttpConnectionsSource {
    public static final String NAME = "self-hosted";

    private final String baseUrl;
    private final String token;
    private final boolean paginates;
    private final Duration requestTimeout;

    public SelfHostedConnectionsSource(
            String baseUrl, String token, boolean paginates, Duration requestTimeout,
            Logger logger) {
        super(NAME, logger, Duration.ofSeconds(10));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "The self-hosted connections source needs INSTA_SELFHOSTED_URL.");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.paginates = paginates;
        this.requestTimeout = requestTimeout;
    }

    /** Ours, so it reads both — unlike most of the marketplace actors. */
    @Override
    public boolean supports(ConnectionType type) {
        return true;
    }

    @Override
    public boolean supportsCursor() {
        return paginates;
    }

    @Override
    protected URI uri(String username, ConnectionType type, int limit, String cursor) {
        StringBuilder url = new StringBuilder(baseUrl)
                .append("/connections?username=").append(encode(username))
                .append("&type=").append(type.name().toLowerCase(Locale.ROOT))
                .append("&limit=").append(limit);
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(encode(cursor));
        }
        return URI.create(url.toString());
    }

    @Override
    protected Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            // A header rather than a query parameter, for the reason ApifyClient gives: a URL ends
            // up in proxy and access logs and a token should not.
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    @Override
    protected Duration requestTimeout() {
        return requestTimeout;
    }
}

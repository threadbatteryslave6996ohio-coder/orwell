package dev.orwell.insta.instagram;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code datadoping/instagram-followers-scraper} — $1.20 / 1,000, no cookies required.
 *
 * <p>A fallback for when the default actor's daily quota is spent, with two limits worth knowing
 * before you rely on it.
 *
 * <p><b>Followers only.</b> Its documented input has no direction, so the chain skips it for a
 * following walk.
 *
 * <p><b>No pagination.</b> It takes a {@code max_count} and returns up to that many, with no way
 * to resume — so a walk using it can never be shown to have seen everything, and therefore never
 * retires an edge. It can add followers to the graph; it cannot detect unfollows. A live run
 * answered {@code max_count: 50} with 48 accounts for an account that has 441, which is exactly
 * why "fewer than asked for" is not treated as proof of the end.
 *
 * <p>Its output <em>does</em> carry an {@code id} despite the documentation not listing one
 * (confirmed on a live run), so rows from it are storable in this id-keyed graph.
 */
public final class DataDopingAdapter implements ConnectionsAdapter {

    @Override
    public String name() {
        return "datadoping";
    }

    @Override
    public String actorId() {
        return "datadoping/instagram-followers-scraper";
    }

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.FOLLOWERS;
    }

    @Override
    public boolean supportsCursor() {
        return false;
    }

    @Override
    public Map<String, Object> input(String username, ConnectionType type, int limit, String cursor) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("usernames", List.of(username));
        input.put("max_count", limit);
        return input;
    }
}

package dev.orwell.insta.instagram;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code logical_scrapers/instagram-followers-scraper} — $2.50 / 1,000, followers only, no
 * pagination. Its output carries {@code pk}, which {@link DatasetFields} already reads as the
 * account id, so unlike {@link DataDopingAdapter} it can populate a graph.
 *
 * <p><b>It expects your Instagram session cookies</b> ({@code sessionid}, {@code csrftoken},
 * {@code ds_user_id}) to work at any scale. That is a materially different trust decision from the
 * cookieless actors — you are handing a third-party actor a live session for your account — so the
 * cookies are read from {@code INSTA_INSTAGRAM_COOKIES} and the adapter simply sends none when
 * that is unset, rather than quietly enabling it.
 *
 * <p>Written from the actor's documentation and <b>not verified against a live run.</b>
 */
public final class LogicalScrapersAdapter implements ConnectionsAdapter {
    private final String cookiesJson;

    public LogicalScrapersAdapter(String cookiesJson) {
        this.cookiesJson = cookiesJson;
    }

    @Override
    public String name() {
        return "logical-scrapers";
    }

    @Override
    public String actorId() {
        return "logical_scrapers/instagram-followers-scraper";
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
        input.put("username", username);
        input.put("max_results", limit);
        if (cookiesJson != null && !cookiesJson.isBlank()) {
            try {
                input.put("cookies", dev.orwell.insta.InstaJson.mapper().readTree(cookiesJson));
            } catch (Exception exception) {
                throw new IllegalArgumentException(
                        "INSTA_INSTAGRAM_COOKIES is not valid JSON.", exception);
            }
        }
        return input;
    }
}

package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.apify.ApifyException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code scraping_solutions/instagram-scraper-followers-following-no-cookies}.
 *
 * <p>The default, and the only adapter here whose behaviour has been confirmed against the live
 * API. It is the only one that does both directions and the only one that paginates, which is what
 * makes it the one a trustworthy diff can be built on.
 *
 * <p>Its catch is the reason this interface exists: on a free plan it caps API use at 1,000 results
 * a day, and when that is reached it <em>succeeds</em> with an empty dataset, admitting the truth
 * only in {@code OUTPUT.success}. {@link #verify} is what turns that into a failure the chain can
 * fall through on rather than a silent "this account has no followers".
 */
public final class ScrapingSolutionsAdapter implements ConnectionsAdapter {
    /** The actor refuses a smaller resultsLimit. */
    private static final int MIN_RESULTS_LIMIT = 50;

    @Override
    public String name() {
        return "scraping-solutions";
    }

    @Override
    public String actorId() {
        return "scraping_solutions/instagram-scraper-followers-following-no-cookies";
    }

    @Override
    public boolean supports(ConnectionType type) {
        return true;
    }

    @Override
    public boolean supportsCursor() {
        return true;
    }

    @Override
    public Map<String, Object> input(String username, ConnectionType type, int limit, String cursor) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("Account", List.of(username));
        input.put("dataToScrape", type.actorValue());
        // The actor floors this; `maxItems` on the run is what actually enforces `limit`.
        input.put("resultsLimit", Math.max(limit, MIN_RESULTS_LIMIT));
        if (cursor != null) {
            input.put("continuationToken", cursor);
        }
        return input;
    }

    @Override
    public void verify(JsonNode output) {
        if (output == null || !output.isObject()) {
            return;
        }
        JsonNode success = output.get("success");
        if (success == null || !success.isBoolean() || success.asBoolean()) {
            return;
        }
        String status = DatasetFields.text(output, "status");
        throw new ApifyException(
                "The Apify actor returned no accounts and reported "
                        + (status == null ? "a failure" : status) + ".",
                ApifyException.Kind.RATE_LIMITED);
    }

    @Override
    public String nextToken(JsonNode output) {
        return ConnectionCursor.nextTokenIn(output);
    }
}

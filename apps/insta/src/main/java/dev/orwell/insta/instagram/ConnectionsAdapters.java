package dev.orwell.insta.instagram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Apify marketplace actors, by name.
 *
 * <p>Only the actors: since sources were introduced this no longer builds the chain, because the
 * chain can now contain things that are not actors at all. {@link ConnectionsSources} owns the
 * chain and reaches these through {@link ApifyConnectionsSource}.
 */
public final class ConnectionsAdapters {

    private ConnectionsAdapters() {
    }

    /**
     * @param cookies value of {@code INSTA_INSTAGRAM_COOKIES}, used only by adapters that need one
     * @return name to factory, in preference order
     */
    static Map<String, Supplier<ConnectionsAdapter>> known(String cookies) {
        Map<String, Supplier<ConnectionsAdapter>> known = new LinkedHashMap<>();
        known.put("scraping-solutions", ScrapingSolutionsAdapter::new);
        known.put("datadoping", DataDopingAdapter::new);
        known.put("logical-scrapers", () -> new LogicalScrapersAdapter(cookies));
        return known;
    }
}

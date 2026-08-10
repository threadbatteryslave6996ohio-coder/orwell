package dev.orwell.insta.instagram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the ordered chain of adapters {@link InstagramService} works through.
 *
 * <p>Order is preference: the first adapter that supports the direction is tried, and the next is
 * only reached when that one refuses — a spent quota, an exhausted balance, a broken actor. Put
 * the cheapest and most capable first.
 */
public final class ConnectionsAdapters {

    private ConnectionsAdapters() {
    }

    /**
     * @param names   comma-separated adapter names, in preference order
     * @param cookies value of {@code INSTA_INSTAGRAM_COOKIES}, used only by adapters that need one
     * @throws IllegalArgumentException naming an adapter that does not exist, rather than silently
     *                                  running with a shorter chain than was asked for
     */
    public static List<ConnectionsAdapter> parse(String names, String cookies) {
        Map<String, Supplier<ConnectionsAdapter>> known = new LinkedHashMap<>();
        known.put("scraping-solutions", ScrapingSolutionsAdapter::new);
        known.put("datadoping", DataDopingAdapter::new);
        known.put("logical-scrapers", () -> new LogicalScrapersAdapter(cookies));

        List<ConnectionsAdapter> chain = new ArrayList<>();
        for (String raw : names.split(",")) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Supplier<ConnectionsAdapter> factory = known.get(name);
            if (factory == null) {
                throw new IllegalArgumentException("Unknown connections adapter: " + name
                        + " (known: " + String.join(", ", known.keySet()) + ")");
            }
            chain.add(factory.get());
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException(
                    "APIFY_CONNECTIONS_ACTORS names no adapters; expected at least one of "
                            + String.join(", ", known.keySet()));
        }
        return List.copyOf(chain);
    }
}

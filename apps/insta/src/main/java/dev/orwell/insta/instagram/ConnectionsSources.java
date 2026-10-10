package dev.orwell.insta.instagram;

import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the ordered chain of sources {@link InstagramService} works through.
 *
 * <p>Order is preference: the first source that supports the direction is tried, and the next is
 * only reached when that one refuses — a spent quota, an exhausted balance, an unreachable service.
 * Put the cheapest and most capable first. A self-hosted scraper usually belongs at the front,
 * since it is the only one that costs nothing per result.
 *
 * <p>This replaced {@link ConnectionsAdapters}, which could only build Apify actors. The three
 * marketplace adapters are unchanged and reached through {@link ApifyConnectionsSource}; the names
 * that select them in {@code APIFY_CONNECTIONS_ACTORS} are unchanged too, so an existing
 * configuration keeps working exactly as it did.
 */
public final class ConnectionsSources {

    private ConnectionsSources() {
    }

    /**
     * @param names comma-separated source names, in preference order
     * @throws IllegalArgumentException naming a source that does not exist, or one whose
     *                                  configuration is missing — rather than silently running with
     *                                  a shorter chain than was asked for, which would show up as
     *                                  an unexplained bill against whichever source did answer
     */
    public static List<ConnectionsSource> parse(
            String names, Settings settings, ApifyClient apify, Logger logger) {

        Map<String, Supplier<ConnectionsSource>> known = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<ConnectionsAdapter>> entry
                : ConnectionsAdapters.known(settings.cookies()).entrySet()) {
            known.put(entry.getKey(),
                    () -> new ApifyConnectionsSource(entry.getValue().get(), apify, logger));
        }
        known.put(SelfHostedConnectionsSource.NAME, () -> new SelfHostedConnectionsSource(
                settings.selfHostedUrl(), settings.selfHostedToken(),
                settings.selfHostedPaginates(),
                Duration.ofSeconds(Math.max(settings.httpTimeoutSeconds(), 1)), logger));
        known.put(RapidApiConnectionsSource.NAME, () -> new RapidApiConnectionsSource(
                RapidApiConnectionsSource.Settings.of(
                        settings.rapidApiHost(), settings.rapidApiKey(),
                        settings.rapidApiFollowersPath(), settings.rapidApiFollowingPath(),
                        settings.rapidApiUserParam(), settings.rapidApiLimitParam(),
                        settings.rapidApiCursorParam(), settings.rapidApiPaginates(),
                        settings.rapidApiReadsFollowing(), settings.httpTimeoutSeconds()),
                logger));

        List<ConnectionsSource> chain = new ArrayList<>();
        for (String raw : names.split(",")) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Supplier<ConnectionsSource> factory = known.get(name);
            if (factory == null) {
                throw new IllegalArgumentException("Unknown connections source: " + name
                        + " (known: " + String.join(", ", known.keySet()) + ")");
            }
            chain.add(factory.get());
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException(
                    "APIFY_CONNECTIONS_ACTORS names no sources; expected at least one of "
                            + String.join(", ", known.keySet()));
        }
        return List.copyOf(chain);
    }

    /**
     * What the sources need from the environment, gathered in one place so the
     * {@code dev.orwell.insta.instagram} package stays unaware of {@code InstaEnvs} — the same rule
     * that keeps it unaware that a command line exists.
     */
    public record Settings(
            String cookies,
            String selfHostedUrl, String selfHostedToken, boolean selfHostedPaginates,
            String rapidApiHost, String rapidApiKey,
            String rapidApiFollowersPath, String rapidApiFollowingPath,
            String rapidApiUserParam, String rapidApiLimitParam, String rapidApiCursorParam,
            boolean rapidApiPaginates, boolean rapidApiReadsFollowing,
            int httpTimeoutSeconds) {
    }
}

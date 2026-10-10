package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.apify.ApifyException;

import java.util.List;
import java.util.Objects;

/**
 * One place a follower or following list can come from, whatever it is underneath.
 *
 * <p>This is the level {@link ConnectionsAdapter} could not reach. An adapter describes an
 * <em>Apify actor</em> — it hands back an actor id and an input map for {@code ApifyClient} to run
 * — which is exactly right for the three marketplace actors and useless for anything that is not
 * on Apify. A self-hosted scraper or a RapidAPI endpoint is its own HTTP service with its own
 * authentication, so it has to own the request rather than describe one.
 *
 * <p>So a source owns its transport and returns raw dataset items.
 * {@link InstagramService} keeps everything that must not vary between sources: mapping items to
 * {@link InstagramAccount} through {@link DatasetFields}, counting unusable rows, encoding the
 * cursor, caching, and the all-or-nothing failover rule.
 *
 * <p>Failures are reported as {@link ApifyException} even from sources that have nothing to do with
 * Apify. That is a naming wart rather than a design one: {@link ApifyException.Kind} is what the
 * chain's failover set and the CLI's exit codes are both keyed on, and giving HTTP sources a
 * parallel exception type would mean two of everything. See {@code CODE_REFACTOR.md}.
 */
public interface ConnectionsSource {

    /** The name used to select this source in {@code APIFY_CONNECTIONS_ACTORS}. */
    String name();

    /**
     * Whether this source can read the given direction at all. Several follower scrapers cannot
     * read a <em>following</em> list, and a chain must skip them rather than send a request they
     * will answer with something else.
     */
    boolean supports(ConnectionType type);

    /**
     * Whether the source can resume where a previous request stopped.
     *
     * <p>A correctness property, not a convenience one. A source that cannot paginate and returns
     * exactly as many accounts as you asked for has told you nothing about whether more exist — so
     * such a walk can never be treated as complete, and can never retire an edge.
     */
    boolean supportsCursor();

    /**
     * One page.
     *
     * @param cursor null for the first page, and only ever non-null when {@link #supportsCursor()}
     *               is true
     * @throws ApifyException with a kind the chain can act on; {@code RATE_LIMITED},
     *                        {@code OUT_OF_CREDIT} and {@code UNAVAILABLE} tell it to try the next
     *                        source, and anything else stops the walk.
     */
    Result fetch(String username, ConnectionType type, int limit, String cursor);

    /**
     * The raw items a source returned, plus where to resume.
     *
     * <p>Deliberately {@link JsonNode} rather than {@link InstagramAccount}: field-name leniency
     * belongs in one place ({@link DatasetFields}), and a source that mapped its own rows would be
     * a second place for {@code full_name} vs {@code fullName} to be got wrong.
     *
     * @param nextToken null when the source reported no more accounts
     */
    record Result(List<JsonNode> items, String nextToken) {

        public Result {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }

        public static Result of(List<JsonNode> items) {
            return new Result(items, null);
        }
    }
}

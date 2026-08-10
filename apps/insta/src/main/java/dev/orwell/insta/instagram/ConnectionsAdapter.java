package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.apify.ApifyException;

import java.util.Map;

/**
 * One way of asking Apify for a follower or following list.
 *
 * <p>Swapping actors is not a matter of changing an id. They disagree on the input field names
 * ({@code Account} vs {@code usernames} vs {@code username}), on the limit field ({@code
 * resultsLimit} vs {@code max_count} vs {@code max_results}), on whether they can scrape
 * <em>following</em> at all, on whether they paginate, and on how they signal a refusal. An adapter
 * is where each of those disagreements lives, so {@link InstagramService} can work in terms of
 * "get me this account's followers" and fall through to the next actor when one is exhausted.
 *
 * <p>Output field names are the one thing adapters mostly do <em>not</em> need to handle:
 * {@link DatasetFields} already reads {@code full_name} and {@code fullName} alike.
 */
public interface ConnectionsAdapter {

    /** The name used to select this adapter in {@code APIFY_CONNECTIONS_ACTORS}. */
    String name();

    /** The Apify actor this adapter drives. */
    String actorId();

    /**
     * Whether this actor can scrape the given direction at all. Several follower scrapers cannot
     * read a <em>following</em> list, and a chain must skip them rather than send a request they
     * will answer with something else.
     */
    boolean supports(ConnectionType type);

    /**
     * Whether the actor can resume where a previous run stopped.
     *
     * <p>This is a correctness property, not a convenience one. An actor that cannot paginate and
     * returns exactly as many accounts as you asked for has told you nothing about whether more
     * exist — so such a walk can never be treated as complete, and can never retire an edge.
     */
    boolean supportsCursor();

    /** The actor input for one page. {@code cursor} is null for the first, and only ever
     *  non-null when {@link #supportsCursor()} is true. */
    Map<String, Object> input(String username, ConnectionType type, int limit, String cursor);

    /**
     * Inspects the run's {@code OUTPUT} record and throws if the actor reported a failure it did
     * not express as a failed run — an empty dataset from a successful-looking run is otherwise
     * indistinguishable from an account with no followers.
     *
     * @throws ApifyException with a kind the chain can act on; {@code RATE_LIMITED} and
     *                        {@code OUT_OF_CREDIT} tell it to try the next actor.
     */
    default void verify(JsonNode output) {
    }

    /** @return the token to resume from, or {@code null} when the list is exhausted. */
    default String nextToken(JsonNode output) {
        return null;
    }
}

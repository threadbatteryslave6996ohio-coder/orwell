package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.insta.InstaJson;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * The opaque {@code cursor} a caller passes back to fetch the next page of a follower or following
 * list.
 *
 * <p>It is not the actor's continuation token verbatim. The actor's own rule is that a token
 * belongs to exactly one account and one scrape direction, and reusing it elsewhere is undefined —
 * so the account and direction are carried inside the cursor and checked on the way back in. A
 * followers cursor replayed against a following walk, or against a different username, is refused
 * here instead of producing a mystery result set from Apify.
 *
 * <p>The encoding is base64url of a small JSON object. That is obfuscation, not security: it makes
 * the cursor a single URL-safe string and discourages callers from parsing it, which is the point.
 * Nothing secret goes in it.
 */
public record ConnectionCursor(String username, ConnectionType type, String token) {

    public ConnectionCursor {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(token, "token");
    }

    /** @return the printable form, handed back as a page's {@code nextCursor}. */
    public String encode() {
        try {
            String payload = InstaJson.mapper().writeValueAsString(
                    java.util.Map.of("u", username, "t", type.name(), "c", token));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode a connections cursor.", exception);
        }
    }

    /**
     * Reads a cursor a caller sent back and returns the actor token inside it.
     *
     * @throws IllegalArgumentException if the cursor is unreadable, or belongs to a different
     *                                  account or scrape direction than the request it arrived on.
     */
    public static String tokenFor(String cursor, String username, ConnectionType type) {
        JsonNode decoded;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            decoded = InstaJson.mapper().readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalArgumentException("That cursor is not readable.");
        }
        if (!username.equals(decoded.path("u").asText(null))
                || !type.name().equals(decoded.path("t").asText(null))) {
            throw new IllegalArgumentException(
                    "That cursor belongs to a different account or list. Start again without one.");
        }
        String token = decoded.path("c").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("That cursor is not readable.");
        }
        return token;
    }

    /**
     * Digs the actor's next continuation token out of a run's {@code OUTPUT} record.
     *
     * <p>The actor documents it as a {@code continuations} array carrying
     * {@code nextContinuationToken}, but only writes one when more accounts remain — so a null
     * here is the ordinary "that was the last page" answer, not a failure. Both the array form and
     * a bare top-level token are accepted, because this is the one part of the contract we cannot
     * verify without a paid run.
     *
     * @return the token, or {@code null} when the list is exhausted.
     */
    static String nextTokenIn(JsonNode output) {
        if (output == null || !output.isObject()) {
            return null;
        }
        String direct = DatasetFields.text(output, "nextContinuationToken", "continuationToken");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        JsonNode continuations = output.get("continuations");
        if (continuations == null || !continuations.isArray()) {
            return null;
        }
        for (JsonNode continuation : continuations) {
            String token =
                    DatasetFields.text(continuation, "nextContinuationToken", "continuationToken");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return null;
    }
}

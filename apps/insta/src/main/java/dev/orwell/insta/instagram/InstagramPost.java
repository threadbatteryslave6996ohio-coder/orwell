package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static dev.orwell.insta.instagram.DatasetFields.number;
import static dev.orwell.insta.instagram.DatasetFields.text;

/**
 * One post.
 *
 * <p>These arrive free: the profile actor returns up to twelve recent posts inside the single
 * dataset item a {@code profile} lookup already pays for, so reading them costs no extra Apify
 * credit. Anything beyond twelve needs the dedicated post actor, which bills per post.
 *
 * <p>Everything but {@code id} may be null — which of these fields an actor fills in varies, and a
 * post with no caption is a post, not a broken row.
 */
public record InstagramPost(
        String id,
        String shortCode,
        String caption,
        Instant takenAt,
        String type,
        Long likesCount,
        Long commentsCount,
        Long videoViewCount,
        String displayUrl,
        String url) {

    /** @return the mapped post, or {@code null} for an item with no id to key it by. */
    static InstagramPost from(JsonNode item) {
        String id = text(item, "id", "postId", "pk");
        if (id == null || id.isBlank()) {
            return null;
        }
        return new InstagramPost(
                id,
                text(item, "shortCode", "short_code", "code"),
                text(item, "caption", "text"),
                takenAt(item),
                text(item, "type", "__typename", "productType"),
                number(item, "likesCount", "likes_count", "likes"),
                number(item, "commentsCount", "comments_count", "comments"),
                number(item, "videoViewCount", "video_view_count", "videoPlayCount"),
                text(item, "displayUrl", "display_url", "imageUrl", "thumbnailUrl"),
                text(item, "url", "postUrl", "permalink"));
    }

    /**
     * Actors disagree on how they express a post's time: an ISO-8601 string in some, epoch seconds
     * in others. Both are accepted, and anything else is left null rather than guessed at — a wrong
     * publication date would silently misorder a whole account's history.
     */
    private static Instant takenAt(JsonNode item) {
        JsonNode value = item == null ? null : item.get("timestamp");
        if (value == null || value.isNull()) {
            value = item == null ? null : item.get("takenAt");
        }
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            long epoch = value.asLong();
            // Values this large are milliseconds; Instagram's own field is seconds.
            return epoch > 100_000_000_000L
                    ? Instant.ofEpochMilli(epoch)
                    : Instant.ofEpochSecond(epoch);
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}

package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static dev.orwell.insta.instagram.DatasetFields.flag;
import static dev.orwell.insta.instagram.DatasetFields.number;
import static dev.orwell.insta.instagram.DatasetFields.text;

/**
 * A public profile's headline numbers. {@code followersCount} / {@code followingCount} are the
 * cheap answer to "how many?" — one actor run, one dataset item — where the list endpoints pay
 * per account returned.
 *
 * <p>Counts are {@link Long} rather than {@code long} on purpose: an actor that could not read a
 * count reports nothing, and reporting that as {@code 0} would be a lie a caller cannot detect.
 */
public record InstagramProfile(
        String id,
        String username,
        String fullName,
        String biography,
        Long followersCount,
        Long followingCount,
        Long postsCount,
        Boolean isPrivate,
        Boolean isVerified,
        String profilePicUrl,
        /**
         * Up to twelve recent posts, included in the same dataset item the counts came from.
         * Never null; empty when the actor returned none.
         */
        List<InstagramPost> latestPosts) {

    /**
     * The posts the profile actor bundles into its one dataset item. Reading them is free — the
     * alternative is paying the post actor for the same twelve.
     */
    private static List<InstagramPost> latestPosts(JsonNode item) {
        JsonNode posts = item == null ? null : item.get("latestPosts");
        if (posts == null || !posts.isArray()) {
            return List.of();
        }
        List<InstagramPost> mapped = new ArrayList<>(posts.size());
        for (JsonNode post : posts) {
            InstagramPost parsed = InstagramPost.from(post);
            if (parsed != null) {
                mapped.add(parsed);
            }
        }
        return List.copyOf(mapped);
    }

    static InstagramProfile from(JsonNode item, String requestedUsername) {
        String username = text(item, "username", "userName");
        return new InstagramProfile(
                text(item, "id", "userId", "pk"),
                username == null ? requestedUsername : username,
                text(item, "fullName", "full_name", "name"),
                text(item, "biography", "bio"),
                number(item, "followersCount", "followers_count", "followers"),
                // "followsCount" is what apify/instagram-profile-scraper calls the following count.
                number(item, "followsCount", "followingCount", "follows_count", "following_count"),
                number(item, "postsCount", "posts_count"),
                flag(item, "private", "isPrivate", "is_private"),
                flag(item, "verified", "isVerified", "is_verified"),
                text(item, "profilePicUrl", "profile_pic_url"),
                latestPosts(item));
    }
}

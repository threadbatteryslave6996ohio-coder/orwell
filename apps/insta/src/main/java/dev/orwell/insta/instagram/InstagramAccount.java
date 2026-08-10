package dev.orwell.insta.instagram;

import com.fasterxml.jackson.databind.JsonNode;

import static dev.orwell.insta.instagram.DatasetFields.flag;
import static dev.orwell.insta.instagram.DatasetFields.text;

/**
 * One account in a follower or following list. Everything but {@code username} may be null: which
 * of these an actor fills in varies, and a missing display name is not a reason to drop the row.
 */
public record InstagramAccount(
        String id,
        String username,
        String fullName,
        Boolean isPrivate,
        Boolean isVerified,
        String profilePicUrl) {

    /** @return the mapped account, or {@code null} for an item with no username to key it by. */
    static InstagramAccount from(JsonNode item) {
        String username = text(item, "username", "userName", "handle");
        if (username == null || username.isBlank()) {
            return null;
        }
        return new InstagramAccount(
                text(item, "id", "userId", "pk"),
                username,
                text(item, "full_name", "fullName", "name"),
                flag(item, "is_private", "isPrivate", "private"),
                flag(item, "is_verified", "isVerified", "verified"),
                text(item, "profile_pic_url", "profilePicUrl", "profilePicture"));
    }
}

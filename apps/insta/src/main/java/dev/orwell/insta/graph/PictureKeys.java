package dev.orwell.insta.graph;

/**
 * The object key a picture is stored under.
 *
 * <p>Content-addressed and fanned out over the first two hex characters, because a flat directory
 * or prefix holding a hundred thousand objects is slow to list on a filesystem and awkward to
 * browse in a bucket. The key carries no account id on purpose — the same image belongs to every
 * account that uses it, and that relationship lives in {@code account_profile_picture}.
 */
final class PictureKeys {
    private static final String PREFIX = "insta/avatars/";

    private PictureKeys() {
    }

    static String keyFor(String contentHash) {
        return PREFIX + contentHash.substring(0, 2) + "/" + contentHash + ".jpg";
    }
}

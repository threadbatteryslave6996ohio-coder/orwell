package dev.orwell.insta.graph;

/**
 * The store you get with {@code INSTA_PICTURE_STORE=none}, and the default.
 *
 * <p>It reports itself disabled rather than accepting bytes and dropping them, so
 * {@link AccountWriter} skips the download too. Fetching an image only to discard it would spend
 * bandwidth and time on nothing — and pulling copies of people's photographs should be something
 * you switched on deliberately, not the default behaviour of a follower sync.
 */
public final class DisabledPictureStore implements PictureStore {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String put(String contentHash, byte[] bytes) {
        throw new UnsupportedOperationException("Picture storage is disabled.");
    }
}

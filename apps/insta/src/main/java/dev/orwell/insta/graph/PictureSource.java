package dev.orwell.insta.graph;

/**
 * Fetches the bytes behind a profile picture URL.
 *
 * <p>Separate from {@link PictureStore} because they fail for different reasons and a test wants to
 * replace one without the other: fetching is somebody else's CDN, storing is your bucket.
 *
 * <p>Costs no Apify credit — the URL arrives inside a scrape you have already paid for, so this is
 * your bandwidth only.
 */
@FunctionalInterface
public interface PictureSource {

    /** @return the image bytes. Throws if the URL is unreachable, expired, or not an image. */
    byte[] fetch(String url) throws Exception;
}

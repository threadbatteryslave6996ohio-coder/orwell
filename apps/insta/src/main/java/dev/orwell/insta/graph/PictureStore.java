package dev.orwell.insta.graph;

/**
 * Where downloaded profile pictures are kept.
 *
 * <p>Addressed by the hash of the bytes rather than by account, so the same image stored for a
 * thousand accounts is a single object. That is not a micro-optimisation: a large share of accounts
 * carry Instagram's default avatar, and keying by account would store it a thousand times.
 *
 * <p>Implementations are chosen from the environment in the same way the cache and the logger are,
 * so pointing production at a different bucket is configuration rather than code.
 */
public interface PictureStore extends AutoCloseable {

    /**
     * Whether pictures should be collected at all. {@code false} makes {@link AccountWriter} skip
     * the download as well as the write — there is no point paying bandwidth for bytes nobody will
     * keep.
     */
    default boolean enabled() {
        return true;
    }

    /**
     * Stores {@code bytes} and returns the key they can be read back under. Implementations may
     * skip the write when the key already exists — the content hash makes that safe, since the same
     * key always means the same bytes.
     *
     * @throws Exception if the bytes could not be stored; the caller records no row rather than
     *                   claiming a picture is in a bucket it never reached.
     */
    String put(String contentHash, byte[] bytes) throws Exception;

    @Override
    default void close() {
    }
}

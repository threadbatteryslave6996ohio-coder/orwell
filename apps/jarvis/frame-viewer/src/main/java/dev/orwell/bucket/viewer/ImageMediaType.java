package dev.orwell.bucket.viewer;

/**
 * What a stored frame actually is, decided from its first few bytes.
 *
 * <p><strong>Nothing records a frame's content type.</strong> The hub validates the envelope and
 * never looks inside the payload — "whatever a producer thinks is worth pushing is what viewers
 * get, which also means the payload need not be an image the server can decode". So there is no
 * column to read this from, and the two obvious shortcuts are both wrong: answering
 * {@code image/jpeg} unconditionally lies to the browser the moment a producer pushes PNG, and
 * answering {@code application/octet-stream} unconditionally turns every frame into a download
 * instead of a picture.
 *
 * <p><strong>It is also the security boundary of this app.</strong> Anything that can reach the
 * hub's {@code POST /frames} can store arbitrary bytes under a source of its choosing — including
 * HTML with a script in it. This viewer then serves those bytes from its own origin, which is
 * exactly the shape of a stored-XSS delivery route. Three things stop it, and all three matter:
 * this returns a type only for a payload whose magic bytes it recognises as an image, the response
 * carries {@code X-Content-Type-Options: nosniff} so a browser will not second-guess that, and
 * anything unrecognised is served as an octet-stream attachment rather than rendered inline.
 * A frame is data the app displays, never a document it executes.
 */
final class ImageMediaType {
    /** What an unrecognised payload is served as: bytes, downloaded, never rendered. */
    static final String UNKNOWN = "application/octet-stream";

    private ImageMediaType() {
    }

    /**
     * The media type for these bytes, or {@link #UNKNOWN} if they are not a recognised image.
     *
     * <p>Deliberately not {@code URLConnection.guessContentTypeFromStream} or
     * {@code Files.probeContentType}: the first consults a mutable system mime table and the second
     * guesses from a filename there isn't one of, and both can answer {@code text/html} — the one
     * answer this must never give.
     */
    static String of(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return UNKNOWN;
        }
        if (startsWith(payload, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(payload, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(payload, 0x47, 0x49, 0x46, 0x38)) {
            return "image/gif";
        }
        // RIFF....WEBP — the four-byte length between the two markers is not part of the check.
        if (startsWith(payload, 0x52, 0x49, 0x46, 0x46) && payload.length >= 12
                && at(payload, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        if (startsWith(payload, 0x42, 0x4D)) {
            return "image/bmp";
        }
        return UNKNOWN;
    }

    /** True when this type is safe to render inline in the page. */
    static boolean isImage(String mediaType) {
        return !UNKNOWN.equals(mediaType);
    }

    private static boolean startsWith(byte[] payload, int... signature) {
        return at(payload, 0, signature);
    }

    private static boolean at(byte[] payload, int offset, int... signature) {
        if (payload.length < offset + signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if ((payload[offset + index] & 0xFF) != signature[index]) {
                return false;
            }
        }
        return true;
    }
}

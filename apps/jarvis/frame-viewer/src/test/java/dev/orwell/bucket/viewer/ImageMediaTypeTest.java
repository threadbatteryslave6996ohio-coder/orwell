package dev.orwell.bucket.viewer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the viewer is willing to call an image.
 *
 * <p>The last test is the one that matters: the hub stores whatever bytes a producer pushes, so a
 * payload that is really a page of HTML is reachable, and it must never come back with a content type
 * that would make a browser run it.
 */
class ImageMediaTypeTest {

    @Test
    void recognisesTheFormatsAProducerActuallySends() {
        assertThat(ImageMediaType.of(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg");
        assertThat(ImageMediaType.of(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
                .isEqualTo("image/png");
        assertThat(ImageMediaType.of(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))).isEqualTo("image/gif");
        assertThat(ImageMediaType.of(bytes(0x42, 0x4D, 0x00, 0x00))).isEqualTo("image/bmp");
    }

    /** WebP needs both markers, twelve bytes apart, not just the RIFF container it shares with WAV. */
    @Test
    void recognisesWebpOnlyWhenBothMarkersArePresent() {
        byte[] webp = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, webp, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, webp, 8, 4);
        assertThat(ImageMediaType.of(webp)).isEqualTo("image/webp");

        byte[] wav = new byte[16];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, wav, 8, 4);
        assertThat(ImageMediaType.of(wav)).isEqualTo(ImageMediaType.UNKNOWN);
    }

    @Test
    void refusesToGuessAtSomethingTooShortToIdentify() {
        assertThat(ImageMediaType.of(null)).isEqualTo(ImageMediaType.UNKNOWN);
        assertThat(ImageMediaType.of(new byte[0])).isEqualTo(ImageMediaType.UNKNOWN);
        assertThat(ImageMediaType.of(bytes(0xFF, 0xD8))).isEqualTo(ImageMediaType.UNKNOWN);
    }

    /**
     * The security case. Anything that can reach the hub's ingest endpoint can store these bytes; if
     * this answered {@code text/html} the viewer would be serving them as a document from its own
     * origin.
     */
    @Test
    void neverIdentifiesMarkupAsSomethingRenderable() {
        byte[] markup = "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

        assertThat(ImageMediaType.of(markup)).isEqualTo(ImageMediaType.UNKNOWN);
        assertThat(ImageMediaType.isImage(ImageMediaType.of(markup))).isFalse();
        assertThat(ImageMediaType.isImage("image/jpeg")).isTrue();
    }

    private static byte[] bytes(int... values) {
        byte[] payload = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            payload[index] = (byte) values[index];
        }
        return payload;
    }
}

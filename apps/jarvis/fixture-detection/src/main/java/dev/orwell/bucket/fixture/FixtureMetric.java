package dev.orwell.bucket.fixture;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * What is actually measured inside a region, and why there is more than one answer.
 *
 * <p>A lamp and a gas burner both "emit light", and a single metric handles them badly. The lamp
 * raises the average brightness of a whole wall; the burner is a handful of strongly coloured pixels
 * in a region that is mostly dark hob. Averaging the burner's region barely moves — a 20×20 flame in
 * a 200×200 region is 1% of the pixels, so a flame that saturates its own pixels lifts the mean by a
 * couple of levels, which is inside the noise of JPEG compression and auto-exposure. Counting bright
 * pixels instead moves from 0% to 1%, which is a factor of infinity.
 *
 * <p>So: pick the metric that matches the <em>shape</em> of what you are looking at, not the fixture
 * category. A big diffuse change is a mean; a small intense one is a count.
 */
public enum FixtureMetric {
    /**
     * Average luminance over the region, 0–255.
     *
     * <p>For a fixture whose light fills the region: a lamp against the wall behind it, a ceiling
     * light seen through a doorway. The value moves with ambient light too, which is what a
     * reference region is for.
     */
    MEAN_LUMINANCE,

    /**
     * Fraction of pixels at or above {@code brightCutoff}, 0–1.
     *
     * <p>For a small intense emitter in a larger region: a bulb, an indicator LED, a hot element.
     * Insensitive to how generously the region was drawn, which is the practical advantage — you can
     * draw a box loosely around the fixture and the number still swings hard.
     */
    BRIGHT_FRACTION,

    /**
     * Fraction of pixels that are both bright and strongly coloured, 0–1.
     *
     * <p>The one for flames and heating elements, and the reason it exists rather than being a
     * "warm colour" test: a gas flame is <em>blue</em>, an electric coil is red, an indicator LED is
     * whatever the manufacturer chose. What they share is not a hue, it is saturation — they are
     * coloured light, where a lamp, daylight and a ceiling fixture are all approximately white.
     *
     * <p>That is what lets this tell "the burner is lit" from "somebody turned the kitchen light on",
     * which mean luminance over the hob cannot do at all. Test it against your own hob before
     * trusting it: a stainless splashback reflecting a warm bulb is coloured light too.
     */
    SATURATED_BRIGHT_FRACTION;

    /**
     * Measures this metric over one region of a frame.
     *
     * <p>Rec. 601 luma ({@code 0.299R + 0.587G + 0.114B}), because that is what matches perceived
     * brightness and what every camera's own auto-exposure is working in. Saturation is the HSV one,
     * {@code (max - min) / max}, which is zero for any shade of grey however bright — the property
     * the coloured-light test rests on.
     *
     * @param brightCutoff     luma at or above which a pixel counts as bright, 0–255
     * @param saturationCutoff saturation at or above which a pixel counts as coloured, 0–1
     */
    public double measure(BufferedImage frame, Region region, int brightCutoff,
            double saturationCutoff) {
        Rectangle box = region.inPixels(frame.getWidth(), frame.getHeight());
        long pixels = 0;
        long lumaTotal = 0;
        long matching = 0;

        for (int py = box.y; py < box.y + box.height; py++) {
            for (int px = box.x; px < box.x + box.width; px++) {
                int rgb = frame.getRGB(px, py);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                int luma = (int) Math.round(0.299 * red + 0.587 * green + 0.114 * blue);
                pixels++;
                lumaTotal += luma;
                if (luma < brightCutoff) {
                    continue;
                }
                if (this == BRIGHT_FRACTION) {
                    matching++;
                } else if (this == SATURATED_BRIGHT_FRACTION
                        && saturation(red, green, blue) >= saturationCutoff) {
                    matching++;
                }
            }
        }

        if (pixels == 0) {
            // Unreachable: inPixels guarantees at least one pixel. Answered rather than divided by.
            return 0;
        }
        return this == MEAN_LUMINANCE
                ? (double) lumaTotal / pixels
                : (double) matching / pixels;
    }

    /** The units this metric reads in, for {@code /fixtures} and the preview overlay. */
    public String unit() {
        return this == MEAN_LUMINANCE ? "luma 0-255" : "fraction 0-1";
    }

    private static double saturation(int red, int green, int blue) {
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max == 0 ? 0 : (double) (max - min) / max;
    }
}

package dev.orwell.bucket.fixture;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each metric actually measures, against images whose content is known exactly.
 *
 * <p>Raw {@link BufferedImage}s rather than encoded JPEGs on purpose: the arithmetic is the subject
 * here, and JPEG's chroma subsampling would blur every block edge and turn exact expectations into
 * approximate ones. {@link FixtureDetectionServiceTest} covers the encoded path.
 */
class FixtureMetricTest {
    /** The whole frame, for the tests that are not about placement. */
    private static final Region WHOLE = new Region(0, 0, 1, 1);
    private static final int CUTOFF = 200;
    private static final double SATURATION = 0.30;

    @Test
    void meanLuminanceReadsTheAverageBrightnessOfTheRegion() {
        BufferedImage grey = flat(100, 100, new Color(128, 128, 128));

        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(grey, WHOLE, CUTOFF, SATURATION))
                .isCloseTo(128, org.assertj.core.data.Offset.offset(1.0));
    }

    /** Rec. 601 weights green far above blue, which is why a lamp's colour temperature matters. */
    @Test
    void meanLuminanceWeightsTheChannelsPerceptually() {
        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(
                flat(10, 10, new Color(0, 255, 0)), WHOLE, CUTOFF, SATURATION))
                .isCloseTo(150, org.assertj.core.data.Offset.offset(1.0));
        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(
                flat(10, 10, new Color(0, 0, 255)), WHOLE, CUTOFF, SATURATION))
                .isCloseTo(29, org.assertj.core.data.Offset.offset(1.0));
    }

    /** The point of a region: what happens elsewhere in the frame must not be measured. */
    @Test
    void measuresOnlyInsideTheRegion() {
        BufferedImage image = flat(100, 100, Color.BLACK);
        fill(image, 0, 0, 50, 50, Color.WHITE);

        Region topLeft = new Region(0, 0, 0.5, 0.5);
        Region bottomRight = new Region(0.5, 0.5, 0.5, 0.5);

        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(image, topLeft, CUTOFF, SATURATION))
                .isCloseTo(255, org.assertj.core.data.Offset.offset(1.0));
        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(image, bottomRight, CUTOFF, SATURATION))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(1.0));
    }

    /**
     * The reason BRIGHT_FRACTION exists. A small emitter barely moves the mean of the region around
     * it — here a bulb covering 1% of its region lifts the mean by two levels, which is inside the
     * noise of JPEG and auto-exposure — while the fraction goes from nothing to 1%.
     */
    @Test
    void brightFractionSeesASmallEmitterThatTheMeanMisses() {
        BufferedImage image = flat(100, 100, Color.BLACK);
        fill(image, 40, 40, 10, 10, Color.WHITE);   // 100 of 10,000 pixels

        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(image, WHOLE, CUTOFF, SATURATION))
                .isLessThan(3.0);
        assertThat(FixtureMetric.BRIGHT_FRACTION.measure(image, WHOLE, CUTOFF, SATURATION))
                .isCloseTo(0.01, org.assertj.core.data.Offset.offset(0.0005));
    }

    @Test
    void brightFractionCountsOnlyPixelsAtOrAboveTheCutoff() {
        BufferedImage image = flat(100, 100, new Color(150, 150, 150));

        // Luma 150: below a cutoff of 200, above one of 100.
        assertThat(FixtureMetric.BRIGHT_FRACTION.measure(image, WHOLE, 200, SATURATION)).isZero();
        assertThat(FixtureMetric.BRIGHT_FRACTION.measure(image, WHOLE, 100, SATURATION))
                .isEqualTo(1.0);
    }

    /**
     * The discriminator the burner detector rests on: coloured light counts, white light does not.
     * A lit hob and a switched-on kitchen lamp both raise brightness over the hob; only one of them
     * is saturated.
     */
    @Test
    void saturatedFractionTellsColouredLightFromWhiteLight() {
        BufferedImage red = flat(50, 50, new Color(255, 40, 0));
        BufferedImage white = flat(50, 50, Color.WHITE);

        assertThat(FixtureMetric.SATURATED_BRIGHT_FRACTION.measure(red, WHOLE, 100, SATURATION))
                .isEqualTo(1.0);
        assertThat(FixtureMetric.SATURATED_BRIGHT_FRACTION.measure(white, WHOLE, 100, SATURATION))
                .isZero();
    }

    /**
     * And it must work for a gas flame, which is blue — the reason this is a saturation test rather
     * than the "warm colour" test the use case first suggests.
     */
    @Test
    void saturatedFractionSeesABlueFlameAsReadilyAsARedElement() {
        BufferedImage blueFlame = flat(50, 50, new Color(40, 120, 255));

        assertThat(FixtureMetric.SATURATED_BRIGHT_FRACTION.measure(blueFlame, WHOLE, 100, SATURATION))
                .isEqualTo(1.0);
    }

    /** A saturated colour that is too dark to be light being emitted does not count. */
    @Test
    void saturatedFractionIgnoresADarkColouredSurface() {
        BufferedImage darkRed = flat(50, 50, new Color(70, 0, 0));

        assertThat(FixtureMetric.SATURATED_BRIGHT_FRACTION.measure(darkRed, WHOLE, 100, SATURATION))
                .isZero();
    }

    /** A region rounding to less than a pixel still reads a value rather than dividing by zero. */
    @Test
    void measuresATinyRegionWithoutFailing() {
        BufferedImage image = flat(100, 100, Color.WHITE);

        assertThat(FixtureMetric.MEAN_LUMINANCE.measure(
                image, new Region(0.5, 0.5, 0.0001, 0.0001), CUTOFF, SATURATION))
                .isCloseTo(255, org.assertj.core.data.Offset.offset(1.0));
    }

    private static BufferedImage flat(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        fill(image, 0, 0, width, height, colour);
        return image;
    }

    private static void fill(BufferedImage image, int x, int y, int width, int height, Color colour) {
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(x, y, width, height);
        graphics.dispose();
    }
}

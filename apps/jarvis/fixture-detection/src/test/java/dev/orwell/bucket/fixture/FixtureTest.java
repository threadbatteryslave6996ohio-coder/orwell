package dev.orwell.bucket.fixture;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fixture's own measurement, and the configuration it refuses.
 *
 * <p>The reference-region tests are the important ones here: they are the difference between a lamp
 * detector that works at 3am and one that reports the lamp on every sunny afternoon.
 */
class FixtureTest {
    private static final Region LEFT = new Region(0, 0, 0.4, 1);
    private static final Region RIGHT = new Region(0.6, 0, 0.4, 1);

    @Test
    void measuresTheRegionWhenThereIsNoReference() {
        Fixture fixture = lamp(null);
        BufferedImage frame = halves(200, 30);

        assertThat(fixture.measure(frame)).isCloseTo(200, Offset.offset(2.0));
    }

    /**
     * The whole point. Daylight lifts the fixture's region <em>and</em> the reference patch, so the
     * difference between them is unchanged — a fixed threshold on the difference holds at noon and at
     * midnight, where a threshold on the region alone cannot do both.
     */
    @Test
    void aReferenceRegionCancelsAmbientLight() {
        Fixture fixture = lamp(RIGHT);

        // Night: lamp region 90, reference wall 20.
        double night = fixture.measure(halves(90, 20));
        // Noon, lamp still on: daylight has added ~120 levels to both.
        double noon = fixture.measure(halves(210, 140));

        assertThat(night).isCloseTo(70, Offset.offset(2.0));
        assertThat(noon).isCloseTo(70, Offset.offset(2.0));

        // Without the reference the same two frames read 90 and 210 — no single threshold works.
        Fixture unreferenced = lamp(null);
        assertThat(unreferenced.measure(halves(90, 20))).isCloseTo(90, Offset.offset(2.0));
        assertThat(unreferenced.measure(halves(210, 140))).isCloseTo(210, Offset.offset(2.0));
    }

    /** And with the lamp off, the difference collapses however bright the day is. */
    @Test
    void theDifferenceCollapsesWhenTheFixtureIsOff() {
        Fixture fixture = lamp(RIGHT);

        assertThat(fixture.measure(halves(20, 20))).isCloseTo(0, Offset.offset(2.0));
        assertThat(fixture.measure(halves(140, 140))).isCloseTo(0, Offset.offset(2.0));
    }

    /** A reference brighter than the region gives a negative reading, which is a real answer. */
    @Test
    void readsNegativeWhenTheReferenceIsBrighter() {
        assertThat(lamp(RIGHT).measure(halves(20, 90))).isCloseTo(-70, Offset.offset(2.0));
    }

    @Test
    void watchesTheSourceItWasGivenAndEveryOneWhenGivenNone() {
        assertThat(lamp(null).watches("cam1")).isTrue();
        assertThat(lamp(null).watches("cam2")).isFalse();

        Fixture anySource = new Fixture("lamp", null, LEFT, null,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 50, 20, 3);
        assertThat(anySource.watches("cam1")).isTrue();
        assertThat(anySource.watches("anything")).isTrue();
    }

    // -----------------------------------------------------------------------
    // Configuration it refuses
    // -----------------------------------------------------------------------

    /**
     * Inverted thresholds can never be satisfied, so the fixture would sit in the dead band forever
     * and report nothing — indistinguishable from a fixture nobody has touched.
     */
    @Test
    void refusesThresholdsThatCanNeverBeCrossed() {
        assertThatThrownBy(() -> new Fixture("lamp", "cam1", LEFT, null,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 10, 50, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("onAbove")
                .hasMessageContaining("offBelow");
    }

    /** Equal thresholds are allowed: a plain threshold with no hysteresis is a valid choice. */
    @Test
    void allowsEqualThresholds() {
        assertThat(new Fixture("lamp", "cam1", LEFT, null,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 50, 50, 3).onAbove()).isEqualTo(50);
    }

    @Test
    void refusesAFixtureWithNothingToIdentifyIt() {
        assertThatThrownBy(() -> new Fixture(" ", "cam1", LEFT, null,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 50, 20, 3))
                .hasMessageContaining("needs a name");
    }

    @Test
    void refusesConfirmFramesBelowOne() {
        assertThatThrownBy(() -> new Fixture("lamp", "cam1", LEFT, null,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 50, 20, 0))
                .hasMessageContaining("confirmFrames");
    }

    @Test
    void refusesARegionOutsideTheFrame() {
        assertThatThrownBy(() -> new Region(0.8, 0, 0.5, 1))
                .hasMessageContaining("x + width");
        assertThatThrownBy(() -> new Region(0, 0, 0, 1))
                .hasMessageContaining("width");
        assertThatThrownBy(() -> new Region(-0.1, 0, 0.5, 1))
                .hasMessageContaining("x must be");
    }

    private static Fixture lamp(Region reference) {
        return new Fixture("lamp", "cam1", LEFT, reference,
                FixtureMetric.MEAN_LUMINANCE, 200, 0.3, 50, 20, 3);
    }

    /** A frame whose left 40% is one grey and right 40% another — the fixture and its reference. */
    private static BufferedImage halves(int leftGrey, int rightGrey) {
        BufferedImage image = new BufferedImage(100, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(leftGrey, leftGrey, leftGrey));
        graphics.fillRect(0, 0, 40, 20);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(40, 0, 20, 20);
        graphics.setColor(new Color(rightGrey, rightGrey, rightGrey));
        graphics.fillRect(60, 0, 40, 20);
        graphics.dispose();
        return image;
    }
}

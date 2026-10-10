package dev.orwell.bucket.fixture;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing being watched: where to look, what to measure there, and what counts as on.
 *
 * @param name             what this fixture is called in the log, the alert and {@code /fixtures}.
 * @param source           the camera it is visible from; null watches whichever source a frame came
 *                         from, which is the single-camera case.
 * @param region           where the fixture is in the frame.
 * @param reference        an optional second region whose value is subtracted from the first. See
 *                         {@link #measure} — this is the ambient-light defence and the most
 *                         important knob here.
 * @param metric           what to measure. See {@link FixtureMetric}.
 * @param brightCutoff     luma at or above which a pixel is "bright", for the fraction metrics.
 * @param saturationCutoff saturation at or above which a pixel is "coloured".
 * @param onAbove          a measurement at or above this means on.
 * @param offBelow         a measurement at or below this means off. Between the two, the state is
 *                         held — that gap is the hysteresis, and it is what stops a fixture right at
 *                         the threshold from reporting a change every second.
 * @param confirmFrames    consecutive frames that must agree before a change is reported. At 1 fps
 *                         this is seconds: 3 rides out a passing shadow, a camera's auto-exposure
 *                         settling, and a single badly compressed frame.
 */
public record Fixture(
        String name,
        String source,
        Region region,
        Region reference,
        FixtureMetric metric,
        int brightCutoff,
        double saturationCutoff,
        double onAbove,
        double offBelow,
        int confirmFrames) {

    public Fixture {
        require(name != null && !name.isBlank(), "a fixture needs a name");
        require(region != null, name + ": a fixture needs a region");
        require(metric != null, name + ": a fixture needs a metric");
        require(brightCutoff >= 0 && brightCutoff <= 255,
                name + ": brightCutoff must be between 0 and 255, got " + brightCutoff);
        require(saturationCutoff >= 0 && saturationCutoff <= 1,
                name + ": saturationCutoff must be between 0 and 1, got " + saturationCutoff);
        // Inverted thresholds are rejected because they cannot be satisfied: nothing is ever both
        // at-or-above onAbove and at-or-below a larger offBelow, so the fixture would sit in the
        // dead band forever and report nothing at all. Equal is allowed — that is a plain threshold
        // with no hysteresis, which is a choice, if a flappier one.
        require(onAbove >= offBelow,
                name + ": onAbove (" + onAbove + ") must not be below offBelow (" + offBelow + ")");
        require(confirmFrames >= 1,
                name + ": confirmFrames must be at least 1, got " + confirmFrames);
    }

    /** True if this fixture is looking at frames from the given source. */
    public boolean watches(String frameSource) {
        return source == null || source.equals(frameSource);
    }

    /**
     * Measures this fixture in one frame.
     *
     * <p>With a {@code reference} region the answer is the difference between the two, and that
     * difference is usually the only thing that works. Absolute brightness cannot tell a lamp from
     * the sun: a region that reads 40 at night with the lamp on reads 180 at noon with it off, so any
     * fixed threshold is wrong for half the day. A reference patch nearby — a stretch of the same
     * wall the lamp does not light — moves with daylight and not with the lamp, so the difference
     * stays put.
     *
     * <p>It also happens to absorb the camera fighting you. Auto-exposure reacts to a lamp switching
     * on by pulling gain <em>down</em>, which flattens the region's own rise; the reference falls at
     * the same time, so the gap widens rather than narrowing. Pick the reference in the same
     * exposure zone as the region and out of the fixture's light.
     */
    public double measure(BufferedImage frame) {
        double value = metric.measure(frame, region, brightCutoff, saturationCutoff);
        if (reference == null) {
            return value;
        }
        return value - metric.measure(frame, reference, brightCutoff, saturationCutoff);
    }

    public Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", name);
        json.put("source", source);
        json.put("region", region.asJson());
        json.put("reference", reference == null ? null : reference.asJson());
        json.put("metric", metric.name());
        json.put("unit", metric.unit());
        json.put("brightCutoff", brightCutoff);
        json.put("saturationCutoff", saturationCutoff);
        json.put("onAbove", onAbove);
        json.put("offBelow", offBelow);
        json.put("confirmFrames", confirmFrames);
        return json;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
